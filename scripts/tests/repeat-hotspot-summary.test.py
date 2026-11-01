"""独立复核T4重复批次；先逐轮验证真实客户端计数，再比较同制品、同参数与清理结果。

每个观众/房间/阶段仍由原hotspot校验器检查；本文件补“不能拿缺模式、换制品或未清理的轮次凑三次”。
分位数只保留各轮各房间原值，绝不把P99平均后称为整体P99。--formal要求3对正式180秒阶段。
"""
import argparse
import json
from pathlib import Path
import runpy


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def verify(directory, formal=False):
    config = read(directory / 'batch-config.json')
    status = read(directory / 'batch-status.json')
    assert status['passed'] and status['error'] is None, status
    assert len(status['runs']) == status['completed'] == config['rounds'] * 2
    awake = read(directory / 'awake-status.json')
    assert awake['requested'] and awake['released'] and awake['error'] is None
    if formal:
        assert config['formal'] and config['rounds'] >= 3 and config['phaseSeconds'] == 180
        assert (config['normalRooms'], config['normalConnections'], config['hotConnections']) == (10, 50, 500)
    # run_path只加载校验函数，__main__不执行；避免复制统计规则后两份逻辑逐渐不一致。
    verify_one = runpy.run_path(str(Path(__file__).with_name('hotspot-summary.test.py')))['verify']
    results = []
    previous_ended = None
    reference_sources = None
    actual_order = []
    for item in status['runs']:
        assert item['passed'] and item['stopped'] and item['error'] is None, item
        assert item['startedAt'] < item['endedAt']
        if previous_ended is not None:
            assert previous_ended <= item['startedAt'], '各轮必须顺序运行，不能并发争抢同机资源'
        previous_ended = item['endedAt']
        actual_order.append((item['round'], item['mode']))
        folder = directory / item['directory']
        launch = read(folder / 'server-launch.json')
        assert launch['pid'] == item['processId'] and launch['jarSha256'] == config['jarSha256']
        assert f"--lab.dispatch.mode={item['mode']}" in launch['arguments']
        assert '--lab.outbound.mode=buffered' in launch['arguments'] and '--lab.cluster.mode=local' in launch['arguments']
        runner = read(folder / 'runner-status.json')
        assert runner['exitCode'] == 0 and runner['captureComplete'] and not runner['timedOut']
        gauges = read(folder / 'cleanup-gauges.json')
        assert len(gauges) == 12 and all(value == 0 for value in gauges.values()), gauges
        load = folder / 'load'
        # 启动器PID必须就是真正采样的JVM；避免Oracle路径包装器造成两个PID口径混淆。
        samples = [json.loads(line) for line in (load / 'samples.jsonl').read_text(encoding='utf-8-sig').splitlines()]
        assert samples and all(row['server']['pid'] == launch['pid'] for row in samples)
        verify_one(load)
        measured = read(load / 'config.json')
        assert not measured.get('diagnostic', False), '诊断轮不能计入T4重复验收'
        assert all(not report.get('diagnostic', {}).get('enabled', False)
                   for report in read(load / 'comparison.json')['reports'])
        for key in ('phaseSeconds', 'normalRooms', 'normalConnections', 'hotConnections', 'latencySampleEvery', 'startDelaySeconds'):
            assert measured[key] == config[key], (key, measured[key], config[key])
        assert measured['dispatchMode'] == item['mode'] and measured['mode'] == 'buffered'
        artifact = read(load / 'jar-sha256.json')
        assert artifact['Hash'] == config['jarSha256'], '不能记录旧JAR的哈希来代表当前运行制品'
        sources = {entry['path'].replace('\\', '/'): entry['sha256'] for entry in read(load / 'source-sha256.json')}
        if reference_sources is None:
            reference_sources = sources
        assert sources == reference_sources, '批次中途不能修改业务或工具源码'
        normal = read(load / 'normal/k6-summary.json')['report']
        hot = read(load / 'hot/k6-summary.json')['report']
        phases = []
        for index, hp in enumerate(hot['rooms'][0]['phases']):
            ordinary = [room['phases'][index] for room in normal['rooms']]
            phases.append(dict(name=hp['name'], normalP99ByRoom=[p['latencyMs']['p(99)'] for p in ordinary],
                normalP95ByRoom=[p['latencyMs']['p(95)'] for p in ordinary],
                normalInputPerSecond=[p['actualInputPerSecond'] for p in ordinary],
                normalLagByRoomMs=[p['maxSendLagMs'] for p in ordinary],
                hotInputPerSecond=hp['actualInputPerSecond'], hotAccepted=hp['accepted'], hotRejected=hp['rejected'],
                hotP99Ms=hp['latencyMs']['p(99)'] if hp['latencyMs'] else None, hotMaxLagMs=hp['maxSendLagMs']))
        results.append(dict(round=item['round'], mode=item['mode'], pid=item['processId'], directory=item['directory'],
                            actual=read(load / 'comparison.json')['client'], phases=phases))
    expected_order = [(number, mode) for number in range(1, config['rounds'] + 1)
                      for mode in (('direct', 'fair') if number % 2 else ('fair', 'direct'))]
    assert actual_order == expected_order, (actual_order, expected_order)
    # 只验证已保存的证据，不依赖本地JAR仍处于原路径；制品另有长期索引和哈希。
    report = dict(passed=True, formal=formal, rounds=config['rounds'], jarSha256=config['jarSha256'],
                  received=sum(item['actual']['received'] for item in results), runs=results,
                  note='Individual room/run quantiles; no averaged P99 advertised as a pooled percentile.')
    target = directory / 'verified-summary.json'
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"PASS {'FORMAL' if formal else 'TOOL'} repetition batch: {len(results)} runs, {report['received']} receipts")
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--formal', action='store_true')
    args = parser.parse_args()
    verify(args.directory, args.formal)
