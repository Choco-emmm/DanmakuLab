"""校验一次真实双进程实验的证据；参数为输出目录，不另造一份服务端发送成功的假数据。

验证目的：拆分不能漏掉热点进程的投递分母，不能误把热点的合法限流当成普通房间拒绝，
也不能让两边各自开始而假称阶段对齐。不对 P99 设“必须变快”的断言，避免把性能结论写死。
用法：python scripts/tests/hotspot-summary.test.py docs/results/.../hotspot-split-1000
"""
import json
import re
import sys
from pathlib import Path


def read(directory, name):
    # PowerShell 归档可能包含 UTF-8 BOM；utf-8-sig 同时兼容带/不带 BOM。
    return json.loads((directory / name).read_text(encoding="utf-8-sig"))


def diagnostic_events(directory):
    events = []
    for line in (directory / "k6-stderr.txt").read_text(encoding="utf-8-sig").splitlines():
        match = re.search(r"T4_DIAGNOSTIC (\{.*\})", line)
        if not match:
            continue
        payload = match.group(1)
        try:
            events.append(json.loads(payload))
        except json.JSONDecodeError:
            events.append(json.loads(json.loads(f'"{payload}"')))
    assert events, f"未找到诊断事件: {directory / 'k6-stderr.txt'}"
    return events


def verify_diagnostic(directory, report, config, require_success=True):
    """验证时间线完整性；失败轮可单独审计，但不能因此被标成验收通过。"""
    events = diagnostic_events(directory)
    assert all(isinstance(event['atMs'], (int, float)) for event in events)
    assert all(current["atMs"] >= previous["atMs"]
               for previous, current in zip(events, events[1:]))
    types = [event["type"] for event in events]
    for event_type in ("started", "input_stopped", "closing_started", "settled"):
        assert types.count(event_type) == 1, (event_type, types)
    assert types[0] == 'started' and types[-1] == 'settled'
    stopped = next(event for event in events if event["type"] == "input_stopped")
    closing = next(event for event in events if event["type"] == "closing_started")
    settled = next(event for event in reversed(events) if event["type"] == "settled")
    assert events.index(stopped) < events.index(closing) < events.index(settled)
    assert closing["atMs"] - stopped["atMs"] >= config["drainSeconds"] * 1000
    # 累计计数必须单调。需求减接收可以临时为负（accepted回复晚到），不能强制裁成0。
    for key in ('sent', 'accepted', 'acceptedDeliveryDemand', 'received', 'closeRequested', 'closeCompleted'):
        assert all(b[key] >= a[key] for a, b in zip(events, events[1:])), key
    assert settled["sent"] == sum(
        phase["sent"] for room in report["rooms"] for phase in room["phases"])
    for key in ('accepted', 'acceptedDeliveryDemand', 'received'):
        assert settled[key] == sum(p[key] for room in report['rooms'] for p in room['phases']), key
    if require_success:
        assert settled["acceptedDeliveryDemand"] == settled["received"]
        assert settled["closeRequested"] == report["totalConnections"]
        assert settled["closeCompleted"] == report["totalConnections"]
        assert settled.get('firstFailure') is None
    close_events = [event for event in events if event["type"] == "close_requested"]
    batch_size = config["diagnosticCloseBatchSize"]
    expected_batches = 1 if batch_size == 0 else (report["totalConnections"] + batch_size - 1) // batch_size
    if require_success:
        assert len(close_events) == expected_batches
    assert all(event["batch"] <= (batch_size or report["totalConnections"])
               for event in close_events)
    assert settled["closeMode"] == ("batched" if batch_size else "all")
    if batch_size:
        assert all(b['atMs'] - a['atMs'] >= config['diagnosticCloseBatchIntervalMs']
                   for a, b in zip(close_events, close_events[1:])), '关闭批次间隔不足'
    return events


def verify(directory):
    status = read(directory, "run-status.json")
    assert status["collectorErrors"] == 0, status
    for key in ("cleanupReturnedToZero", "serverCountersMatch", "processesPassed", "phaseAlignmentPassed"):
        assert status[key] is True, (key, status)
    schedule = read(directory, "schedule.json")
    roles = ("normal", "hot") if schedule["splitProcesses"] else ("combined",)
    assert set(status["processIds"]) == set(roles)
    assert len(set(status["processIds"].values())) == len(roles)
    totals = dict.fromkeys(("sent", "accepted", "rejected", "acceptedDeliveryDemand", "received"), 0)
    reports = []
    config = read(directory, "config.json")
    for role in roles:
        folder = directory / role if schedule["splitProcesses"] else directory
        payload = read(folder, "k6-summary.json")
        report = payload["report"]
        reports.append(report)
        if config.get("diagnostic", False):
            verify_diagnostic(folder, report, config)
        child = read(folder, "k6-status.json")

        assert child["exitCode"] == 0 and child["captureComplete"] and not child["timedOut"]
        assert child["processId"] == status["processIds"][role]
        assert report["role"] == role
        assert report["plannedStartAtMs"] == schedule["plannedStartAtMs"]
        if schedule["splitProcesses"]:
            assert 0 < report["readyAtMs"] < report["plannedStartAtMs"] <= report["startedAtMs"]
        else:
            assert report["plannedStartAtMs"] == 0 < report["readyAtMs"] <= report["startedAtMs"]
        assert 0 <= report["startLagMs"] < 1000
        assert all(threshold["ok"] for metric in payload["metrics"]["metrics"].values()
                   for threshold in metric.get("thresholds", {}).values()), 'k6 thresholds failed'
        assert report["joined"] == report["totalConnections"]
        for key in ("errors", "duplicates", "orderViolations", "isolationFailures", "unexpectedCloses",
                    "missingAccepted", "missingResults"):
            assert report[key] == 0, (role, key)
        for room in report["rooms"]:
            if role != "combined":
                assert (room["name"] == "hot") == (role == "hot")
            assert len(room["phases"]) == 4
            for phase in room["phases"]:
                assert phase["sent"] == phase["plannedInputMessages"]
                assert phase["sent"] == phase["accepted"] + phase["rejected"]
                assert phase["acceptedDeliveryDemand"] == phase["accepted"] * room["connections"]
                assert phase["received"] == phase["acceptedDeliveryDemand"] and phase["missingAccepted"] == 0
                reasons = phase["rejectionReasons"]
                assert reasons["connection_rate_limit"] == reasons["other"] == 0
                assert phase["rejected"] == reasons["room_rate_limit"]
                if room["name"] != "hot":
                    assert phase["rejected"] == 0
                # 抽样按每隔 N 个观众抽一个；向上取整后再乘接受输入，不能错用全部观众数。
                sampled_viewers = (room["connections"] + report["latencySampleEvery"] - 1) // report["latencySampleEvery"]
                assert phase["latencySamples"] == phase["accepted"] * sampled_viewers
                for key in totals:
                    totals[key] += phase[key]
    delta = read(directory, "counter-delta.json")
    assert totals == delta["client"], (totals, delta["client"])
    for server, client in (("lab.messages.input", "sent"), ("lab.messages.accepted", "accepted"),
                           ("lab.messages.rejected", "rejected"), ("lab.deliveries.expected", "acceptedDeliveryDemand"),
                           ("lab.deliveries.enqueued", "acceptedDeliveryDemand"), ("lab.deliveries.submitted", "received")):
        assert delta["server"][server] == totals[client], server
    assert delta["server"]["lab.writes.failed"] == delta["server"]["lab.outbound.dropped"] == 0
    comparison = read(directory, "comparison.json")
    assert comparison["client"] == totals and comparison["reports"] == reports
    print(f"PASS: {directory}; {sum(r['joined'] for r in reports)} connections; {totals['received']} actual deliveries")


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
