"""只读T6归档，独立复核原始计数、重连代际、1013、毫秒桶和资源；不导入客户端汇总函数。"""
import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path


def read(folder, name):
    return json.loads((folder / name).read_text(encoding='utf-8-sig'))


def gauges(snapshot):
    """这里每次快照是顺序HTTP读取，检查采到的边界，不能冒充同一瞬间的完整状态。"""
    return {item['name']: item['measurements'][0]['value'] for item in snapshot['metrics']}


def verify(folder, formal=False):
    config = read(folder, 'config.json')
    report, raw = read(folder, 'client/summary.json'), read(folder, 'client/observations.json')
    status, process = read(folder, 'run-status.json'), read(folder, 'python-status.json')
    n, affected, cycles = config['connections'], config['affected'], config['cycles']
    stable = n - affected
    assert process['exitCode'] == 0 and process['captureComplete'] and not process['timedOut']
    assert not process['captureErrors'] and not process['cleanupErrors']
    assert status['passed'] and status['collectorErrors'] == 0
    assert report['passed'] and not report['fatal'] and all(report['checks'].values())
    assert not report['failures'] and not report['failureDetails']
    assert report['duplicates'] == report['orderViolations'] == report['protocolErrors'] == 0
    assert report['connections'] == n and report['stableConnections'] == stable
    assert report['affectedConnections'] == affected and report['cycles'] == cycles
    assert report['initialMembershipNeverShrinks']
    # 同一逻辑观众跨代必须拿到全新连接ID；始终在线组不应被脚本偷偷重建。
    joins = report['joins']
    assert len(joins) == len({row['connectionId'] for row in joins}) == n + affected * cycles
    assert report['uniqueConnectionIds'] == len(joins) and not any(row['probe'] for row in joins)
    assert {(row['viewer'], row['generation']) for row in joins} == (
        {(v, 0) for v in range(n)} | {(v, c) for c in range(1, cycles + 1) for v in range(stable, n)})
    assert report['plannedDisconnects'] == affected * cycles
    attempts = defaultdict(list)
    for row in report['retries']:
        assert row['outcome'] in ('joined', 'rejected_1013', 'other_failure')
        attempts[(row['viewer'], row['cycle'], row['probe'])].append(row)
        cap = min(30000, 1000 * 2 ** min(row['attempt'], 5))
        assert row['capMs'] == cap and 0 <= row['delayMs'] < cap
        # Windows定时器有轻微测量误差；允许1ms，不允许跳过整段抖动等待。
        assert row['waitedMs'] + 1 >= row['delayMs'] and row['endedAt'] >= row['startedAt']
        if row['outcome'] == 'rejected_1013':
            assert row['closeCode'] == 1013 and row['error'] == 'EOFError:服务端关闭帧:03f5'
    assert len(attempts) == affected * cycles + config['probes']
    for (viewer, cycle, probe), rows in attempts.items():
        assert [r['attempt'] for r in rows] == list(range(len(rows)))
        if probe:
            assert viewer >= n and cycle == 0 and len(rows) == config['probeAttempts']
            assert all(r['outcome'] == 'rejected_1013' for r in rows)
        else:
            assert stable <= viewer < n and 1 <= cycle <= cycles
            assert rows[-1]['outcome'] == 'joined' and len(rows) <= config['maxAttempts']
            assert all(r['outcome'] != 'joined' for r in rows[:-1])
            assert any(j['connectionId'] == rows[-1]['connectionId'] and j['viewer'] == viewer and j['generation'] == cycle for j in joins)
    assert report['observedAdmissionRejections'] == sum(r['outcome'] == 'rejected_1013' for r in report['retries'])
    assert report['probeResults'] == [True] * config['probes']
    assert len(report['recovery']) == cycles
    for cycle, recovery in enumerate(report['recovery'], 1):
        assert recovery['cycle'] == cycle and recovery['allRejoined'] and recovery['recovered'] == affected
        successes = [r for r in report['retries'] if not r['probe'] and r['cycle'] == cycle and r['outcome'] == 'joined']
        elapsed = (max(r['endedAt'] for r in successes) - recovery['droppedAt']) * 1000
        assert abs(elapsed - recovery['recoveryMs']) < .001
        assert 0 <= elapsed <= config['recoveryDeadline'] * 1000 + 100
    offset = 0
    assert len(report['phases']) == 2 * cycles + 1
    assert len(raw['perViewer']) == n
    for p, phase in enumerate(report['phases']):
        churn = p % 2 == 1
        name = 'baseline' if p == 0 else f"{'churn' if churn else 'recovered'}-{(p + 1) // 2}"
        count = config['rate'] * (config['churnSeconds'] if churn else config['steadySeconds'])
        cohort = stable if churn else n
        hi = offset + count
        assert phase['name'] == name and phase['churn'] == churn and phase['passed']
        assert phase['planned'] == phase['sent'] == phase['accepted'] == count and phase['rejected'] == 0
        assert not phase['rejectionReasons'] and raw['status'][offset:hi] == [1] * count
        assert phase['requiredViewers'] == cohort
        assert phase['nominalRequiredDemand'] == phase['acceptedRequiredDemand'] == count * cohort
        assert phase['requiredReceived'] == sum(raw['requiredReceipts'][offset:hi]) == count * cohort
        assert raw['requiredReceipts'][offset:hi] == [cohort] * count
        assert phase['allInitialViewerPotentialDemand'] == count * n and phase['missingAccepted'] == 0
        counts = [row[p] for row in raw['perViewer']]
        assert phase['perViewerReceived'] == counts and counts[:cohort] == [count] * cohort
        assert all(0 <= value <= count for value in counts)
        assert phase['allReceived'] == sum(counts) == sum(raw['allReceipts'][offset:hi])
        assert all(cohort <= value <= n for value in raw['allReceipts'][offset:hi])
        assert config['rate'] * .95 <= phase['actualInputPerSecond'] <= config['rate'] * 1.05
        assert phase['maxSendLagMs'] < 1000
        hist = raw['histograms'][p]
        samples = sum(number for value, number in hist)
        assert samples == phase['latencyMs']['samples'] == count * len(range(0, cohort, config['latencySampleEvery']))
        for key, fraction in (('p50', .5), ('p95', .95), ('p99', .99)):
            total = 0
            for value, number in hist:
                total += number
                if total >= math.ceil(samples * fraction):
                    assert phase['latencyMs'][key] == value
                    break
        assert phase['latencyMs']['p95'] < 200 and phase['latencyMs']['p99'] < 500
        assert phase['latencyMs']['overflowAbove120000ms'] == 0
        window = report['phaseWindows'][p]
        assert window['name'] == name and window['endedAt'] > window['beganAt']
        offset = hi
    assert all(len(raw[key]) == offset for key in ('status', 'timestamps', 'requiredReceipts', 'allReceipts', 'sequenceByInput'))
    assert len(set(raw['sequenceByInput'])) == offset and min(raw['sequenceByInput']) == 1
    assert report['plannedInputs'] == report['sent'] == report['accepted'] == offset and report['rejected'] == 0
    assert report['requiredReceived'] == sum(raw['requiredReceipts'])
    assert report['allReceived'] == sum(raw['allReceipts'])
    before, after = gauges(read(folder, 'server-before.json')), gauges(read(folder, 'server-after.json'))
    delta = read(folder, 'counter-delta.json')['server']
    for name, value in delta.items():
        assert value == after.get(name, 0) - before.get(name, 0)
    assert delta['lab.messages.input'] == delta['lab.messages.accepted'] == offset and delta['lab.messages.rejected'] == 0
    assert delta['lab.connections.rejected'] == report['observedAdmissionRejections']
    assert report['requiredReceived'] <= report['allReceived'] <= delta['lab.deliveries.submitted'] <= delta['lab.deliveries.expected'] <= offset * n
    for name in ('lab.connections','lab.rooms','lab.outbound.bytes','lab.outbound.messages','lab.outbound.slots',
                 'lab.outbound.tasks','lab.outbound.writers.active','lab.outbound.writes.inflight',
                 'lab.dispatch.bytes','lab.dispatch.messages','lab.dispatch.rooms','lab.dispatch.ready'):
        assert after[name] == 0, (name, after[name])
    samples = [json.loads(line) for line in (folder / 'samples.jsonl').read_text(encoding='utf-8-sig').splitlines()]
    assert samples
    for row in samples:
        assert 'error' not in row and row['server'] is not None
        values = gauges(row['snapshot'])
        for name, bound in (('lab.connections',n),('lab.outbound.slots',n),('lab.outbound.writes.inflight',n),
                            ('lab.outbound.bytes',134217728),('lab.dispatch.bytes',4194304),('lab.outbound.writers.active',8)):
            assert 0 <= values[name] <= bound, (name, values[name])
    if formal:
        assert n == 100 and affected == 50 and cycles == 3 and config['steadySeconds'] == config['churnSeconds'] == 10
    print(f"PASS {'FORMAL' if formal else 'TOOL'} T6: {folder}; required={report['requiredReceived']}; joins={len(joins)}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--formal', action='store_true')
    args = parser.parse_args()
    verify(args.directory, args.formal)
