"""独立复核真实T2归档：只读原始分片，不调用生产汇总函数来证明它自己正确。"""
import argparse
import json
import math
from pathlib import Path


def read(folder, name):
    return json.loads((folder / name).read_text(encoding='utf-8-sig'))


def verify(folder, formal=False):
    config, status = read(folder, 'config.json'), read(folder, 'run-status.json')
    report = read(folder, 'client/summary.json')
    process = read(folder, 'python-status.json')
    assert process['exitCode'] == 0 and process['captureComplete'] and not process['timedOut']
    assert status['processPassed'] and status['toolValid'] and status['serverInputCountersMatch']
    assert status['cleanupReturnedToZero'] and status['collectorErrors'] == 0
    assert report['toolValid'] and report['metadataConsistent'] and report['startupAligned']
    rates = [int(value) for value in config['rates'].split(',')]
    sources = [read(folder, f'client/worker-{i}/summary.json') for i in range(config['workers'])]
    assert len({r['pid'] for r in sources}) == config['workers']
    viewers = [i for source in sources for i in range(source['begin'], source['end'])]
    assert viewers == list(range(config['connections']))
    assert all(r['joined'] == r['end'] - r['begin'] and not r['fatal'] for r in sources)
    assert all(r['duplicates'] == r['orderViolations'] == r['protocolErrors'] == 0 for r in sources)
    sender = sources[0]
    total = sum(rates) * (config['phaseSeconds'] + config['warmupSeconds'])
    assert sender['sent'] == total and len(sender['status']) == total
    assert 0 not in sender['status']
    for index in range(total):
        # 发送者本身也可能漏收，不能把它的序号0当成所有其他观众的正确序号。
        observed = {source['sequenceByInput'][index] for source in sources if source['sequenceByInput'][index]}
        assert len(observed) <= 1
    for source in sources:
        assert len(source['receipts']) == len(source['timestamps']) == len(source['sequenceByInput']) == total
        for index, receipts in enumerate(source['receipts']):
            assert 0 <= receipts <= source['end'] - source['begin']
            if receipts:
                assert sender['status'][index] == 1
                assert source['timestamps'][index] == sender['timestamps'][index]
                assert source['sequenceByInput'][index] > 0
    offset = 0
    for p, (rate, phase) in enumerate(zip(rates, report['phases'])):
        lo = offset + rate * config['warmupSeconds']
        hi = lo + rate * config['phaseSeconds']
        accepted = sender['status'][lo:hi].count(1)
        rejected = sender['status'][lo:hi].count(2)
        received = sum(sum(source['receipts'][lo:hi]) for source in sources)
        counts = [row[p * 2 + 1] for source in sources for row in source['perViewer']]
        assert phase['accepted'] == accepted and phase['rejected'] == rejected
        assert sum(phase['rejectionReasons'].values()) == rejected
        assert phase['sent'] == rate * config['phaseSeconds'] == accepted + rejected
        assert phase['allInputDeliveryDemand'] == rate * config['phaseSeconds'] * config['connections']
        assert phase['acceptedDeliveryDemand'] == accepted * config['connections']
        assert phase['received'] == received == sum(counts)
        assert phase['perViewerReceived'] == counts and all(0 <= count <= accepted for count in counts)
        assert phase['missingAccepted'] == accepted * config['connections'] - received
        assert rate * .95 <= phase['actualInputPerSecond'] <= rate * 1.05 and phase['maxSendLagMs'] < 1000
        # 独立合并非零桶，验证总体样本量与分位数；样本数来自实际抽样观众收到多少。
        bins = {}
        for source in sources:
            for value, count in source['histograms'][p]['bins']:
                bins[value] = bins.get(value, 0) + count
        samples = sum(bins.values())
        sampled_received = sum(count for index, count in enumerate(counts) if index % config['latencySampleEvery'] == 0)
        assert samples == sampled_received == phase['latencyMs']['samples']
        for name, fraction in (('p50', .5), ('p95', .95), ('p99', .99)):
            cumulative, expected = 0, None
            for value, count in sorted(bins.items()):
                cumulative += count
                if cumulative >= math.ceil(samples * fraction):
                    expected = value
                    break
            assert phase['latencyMs'][name] == expected
        offset = hi
    delta = read(folder, 'counter-delta.json')['server']
    assert delta['lab.messages.input'] == total
    assert delta['lab.messages.accepted'] == report['acceptedIncludingWarmup'] == sender['status'].count(1)
    assert delta['lab.messages.rejected'] == report['rejectedIncludingWarmup'] == sender['status'].count(2)
    received = sum(sum(source['receipts']) for source in sources)
    assert received == report['receivedIncludingWarmup']
    if status['allPhasesSloPassed']:
        assert report['allPhasesSloPassed'] and all(p['candidateSloPassed'] for p in report['phases'])
        assert delta['lab.deliveries.expected'] == delta['lab.deliveries.enqueued'] == delta['lab.deliveries.submitted'] == received
        assert delta['lab.outbound.dropped'] == delta['lab.writes.failed'] == delta['lab.dispatch.discarded'] == 0
    for line in (folder / 'samples.jsonl').read_text(encoding='utf-8-sig').splitlines():
        sample = json.loads(line)
        assert 'error' not in sample
        metrics = {item['name']: item['measurements'][0]['value'] for item in sample['snapshot']['metrics']}
        assert 0 <= metrics['lab.outbound.bytes'] <= 134217728
        assert 0 <= metrics['lab.dispatch.bytes'] <= 4194304
        assert 0 <= metrics['lab.outbound.writes.inflight'] <= config['connections']
    if formal:
        assert status['formalParametersSatisfied'] and config['connections'] == 1000
        assert config['phaseSeconds'] == 180 and rates == [5, 20, 50, 100]
    print(f"PASS {'FORMAL' if formal else 'TOOL'}: {folder}; toolValid=True; allPhasesSloPassed={status['allPhasesSloPassed']}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--formal', action='store_true')
    args = parser.parse_args()
    verify(args.directory, args.formal)
