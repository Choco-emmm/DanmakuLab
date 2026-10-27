"""复核 T3/T5 实际归档，默认验证成功；--expect-failure 仅验证失败证据完整，不冒充验收通过。"""
import argparse
import json
import math
from pathlib import Path


def read(folder, name):
    return json.loads((folder / name).read_text(encoding='utf-8-sig'))


def verify(folder, formal=False, expect_failure=False):
    report = read(folder, 'client/summary.json')
    config = read(folder, 'config.json')
    status = read(folder, 'run-status.json')
    process = read(folder, 'python-status.json')
    delta = read(folder, 'counter-delta.json')
    assert len(report['perViewerMeasured']) == config['connections']
    normal = config['connections'] - config['slowConnections']
    messages = config['seconds'] * config['rate']
    assert report['normalExpected'] == normal * messages
    assert report['normalReceived'] == sum(report['perViewerMeasured'][config['slowConnections']:])
    assert report['slowReceived'] == sum(report['perViewerMeasured'][:config['slowConnections']])
    assert report['slowPotentialDemandAtInitialMembership'] == config['slowConnections'] * messages
    assert report['passed'] == all(report['checks'].values())
    assert process['captureComplete'] and not process['timedOut']
    assert status['collectorErrors'] == 0 and status['cleanupReturnedToZero']
    # 异步版本在途写也必须归零。旧制品无此Gauge，不倒改历史归档或伪造零值指标。
    after = read(folder, 'server-after.json')
    for item in after['metrics']:
        if item['name'] == 'lab.outbound.writes.inflight':
            assert item['measurements'][0]['value'] == 0
    assert delta['client'] == report
    assert report['requestBytes'] == config['inputBytes'] == 256
    assert report['formalDurationSatisfied'] == (config['seconds'] >= (900 if config['scenario'] == 'T3' else 1800))
    if expect_failure:
        assert not report['passed'] and process['exitCode'] != 0 and not status['processPassed']
        print(f'VALID FAILED EVIDENCE: {folder}; normal={report["normalReceived"]}/{report["normalExpected"]}')
        return
    assert process['exitCode'] == 0 and status['processPassed'] and status['serverCountersMatch']
    assert report['passed'] and report['normalReceived'] == report['normalExpected']
    assert report['normalMissingAccepted'] == report['duplicates'] == report['orderViolations'] == report['protocolErrors'] == 0
    assert report['joined'] == config['connections']
    total = (config['seconds'] + config['warmupSeconds']) * config['rate']
    assert report['submittedIncludingWarmup'] == report['acceptedIncludingWarmup'] == total
    assert report['normalWarmupReceived'] == normal * config['warmupSeconds'] * config['rate']
    assert report['normalLatencyMs']['samples'] == math.ceil(normal / 10) * messages
    assert report['normalLatencyMs']['p95'] < 200 and report['normalLatencyMs']['p99'] < 500
    assert report['normalLatencyMs']['overflowAbove120000ms'] == 0
    assert config['rate'] * .95 <= report['actualInputPerSecond'] <= config['rate'] * 1.05
    assert report['maxSendLagMs'] < 1000
    assert delta['server']['lab.messages.input'] == delta['server']['lab.messages.accepted'] == total
    if config['scenario'] == 'T5':
        expected = config['connections'] * total
        for name in ('lab.deliveries.expected', 'lab.deliveries.enqueued', 'lab.deliveries.submitted'):
            assert delta['server'][name] == expected, name
        assert delta['server']['lab.outbound.dropped'] == delta['server']['lab.dispatch.discarded'] == delta['server']['lab.writes.failed'] == 0
    else:
        assert len(report['slowPerViewer']) == config['slowConnections']
        if formal:
            assert status['backpressureObserved']
            assert config['slowConnections'] * 100 == config['connections']
        for peer in report['slowPerViewer']:
            # 允许测量边界的一次64KiB预读和最后7秒排空；不把它描述成精确网络链路速率。
            assert peer['readBytes'] <= config['slowBytesPerSecond'] * (report['actualMeasureSeconds'] + 9) + 65536
    if formal:
        assert report['formalDurationSatisfied']
        assert report['actualMeasureSeconds'] >= config['seconds'] * .95
        assert config['connections'] == 1000 and config['rate'] == 20
    for line in (folder / 'samples.jsonl').read_text(encoding='utf-8-sig').splitlines():
        row = json.loads(line)
        assert 'error' not in row
        metrics = {item['name']: item['measurements'][0]['value'] for item in row['snapshot']['metrics']}
        assert 0 <= metrics.get('lab.outbound.bytes', 0) <= 134217728
        assert 0 <= metrics.get('lab.dispatch.bytes', 0) <= 4194304
        if 'lab.outbound.writes.inflight' in metrics:
            assert 0 <= metrics['lab.outbound.writes.inflight'] <= config['connections']
    print(f'PASS{ " FORMAL" if formal else " TOOL CHECK"}: {folder}; normal={report["normalReceived"]}/{report["normalExpected"]}; p99={report["normalLatencyMs"]["p99"]}ms')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--formal', action='store_true')
    parser.add_argument('--expect-failure', action='store_true')
    args = parser.parse_args()
    verify(args.directory, args.formal, args.expect_failure)
