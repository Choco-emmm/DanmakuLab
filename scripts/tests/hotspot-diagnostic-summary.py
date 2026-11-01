"""审计短诊断的成功与失败证据，不把时间线完整、收到消息或退出0单独当作全部通过。

--evidence-only只检查可审计性，仍原样写passed=false；用于检查刻意注入的失败轮。
"""
import argparse
import json
from pathlib import Path
import runpy

RULES = runpy.run_path(str(Path(__file__).with_name('hotspot-summary.test.py')))


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def audit(directory):
    config, status = read(directory / 'config.json'), read(directory / 'run-status.json')
    assert config['diagnostic'] and not config['formalEligible'], '正式模式不能混入诊断'
    schedule = read(directory / 'schedule.json')
    roles = schedule['roles']
    reports, details, errors = [], [], []
    for role in roles:
        folder = directory / role if schedule['splitProcesses'] else directory
        summary = read(folder / 'k6-summary.json')
        report = summary['report']
        reports.append(report)
        # 这里只放宽“必须成功”，仍严格校验日志可解析、事件顺序、累计值与最终报告相符。
        events = RULES['verify_diagnostic'](folder, report, config, require_success=False)
        child = read(folder / 'k6-status.json')
        failed = [dict(metric=name, threshold=rule, observed=metric['values'])
                  for name, metric in summary['metrics']['metrics'].items()
                  for rule, value in metric.get('thresholds', {}).items() if not value['ok']]
        stopped = next(e for e in events if e['type'] == 'input_stopped')
        closing = next(e for e in events if e['type'] == 'closing_started')
        final = events[-1]
        details.append(dict(role=role, exitCode=child['exitCode'], failedThresholds=failed,
            eventCount=len(events), inputStoppedAtMs=stopped['atMs'], closingAtMs=closing['atMs'],
            settledAtMs=final['atMs'], closeDurationMs=final['atMs']-closing['atMs'],
            firstFailure=final.get('firstFailure'), closeRequested=final['closeRequested'],
            closeCompleted=final['closeCompleted'], maxSendLagMs=final['maxObservedSendLagMs'],
            maxTickDriftMs=final['maxTickDriftMs'],
            maxObservedDemandMinusReceipts=max(e['acceptedDeliveryDemand']-e['received'] for e in events),
            demandMinusReceiptsAtClose=closing['acceptedDeliveryDemand']-closing['received'],
            # 逐秒观测差值不是精确服务端队列长度，不能改名为“积压消息数”。
            injectionObserved=any(e['type']=='injected_stall_started' for e in events)))
        if child['exitCode'] != 0 or child['timedOut'] or not child['captureComplete'] or failed:
            errors.append(f'{role}: process/threshold failure')
    gauges = read(directory / 'cleanup-gauges.json')
    assert len(gauges) == 12, '清理证据必须保留12项，未知值必须为null'
    clean = all(value == 0 for value in gauges.values())
    delta = read(directory / 'counter-delta.json')
    observed = {key: sum(p[key] for r in reports for room in r['rooms'] for p in room['phases'])
                for key in ('sent', 'accepted', 'rejected', 'acceptedDeliveryDemand', 'received')}
    assert observed == delta['client'], '最终客户端汇总与对账文件不一致'
    for key in ('processesPassed', 'phaseAlignmentPassed', 'serverCountersMatch', 'cleanupReturnedToZero'):
        if not status[key]: errors.append(key)
    if status['collectorErrors']: errors.append('collectorErrors')
    if not clean: errors.append('cleanup gauges nonzero/unknown')
    if not errors:
        # 通过轮还必须走原有完整验收，不能因增加诊断而降低房间/顺序/实际速率要求。
        RULES['verify'](directory)
    result = dict(evidenceComplete=True, passed=not errors, formalEligible=False, roles=details,
                  client=observed, cleanupGauges=gauges, errors=errors,
                  note='Diagnostic only. Demand minus receipts is observed difference, not exact server backlog.')
    (directory / 'diagnostic-audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--evidence-only', action='store_true')
    args = parser.parse_args()
    result = audit(args.directory)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['passed'] or args.evidence_only else 1)
