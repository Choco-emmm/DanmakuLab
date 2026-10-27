"""校验一次真实双进程实验的证据；参数为输出目录，不另造一份服务端发送成功的假数据。

验证目的：拆分不能漏掉热点进程的投递分母，不能误把热点的合法限流当成普通房间拒绝，
也不能让两边各自开始而假称阶段对齐。不对 P99 设“必须变快”的断言，避免把性能结论写死。
用法：python scripts/tests/hotspot-summary.test.py docs/results/.../hotspot-split-1000
"""
import json
import sys
from pathlib import Path


def read(directory, name):
    # PowerShell 归档可能包含 UTF-8 BOM；utf-8-sig 同时兼容带/不带 BOM。
    return json.loads((directory / name).read_text(encoding="utf-8-sig"))


def verify(directory):
    status = read(directory, "run-status.json")
    assert status["collectorErrors"] == 0, status
    for key in ("cleanupReturnedToZero", "serverCountersMatch", "processesPassed", "phaseAlignmentPassed"):
        assert status[key] is True, (key, status)
    assert status["processIds"]["normal"] != status["processIds"]["hot"]
    schedule = read(directory, "schedule.json")
    assert schedule["splitProcesses"] is True
    totals = dict.fromkeys(("sent", "accepted", "rejected", "acceptedDeliveryDemand", "received"), 0)
    reports = []
    for role in ("normal", "hot"):
        report = read(directory / role, "k6-summary.json")["report"]
        reports.append(report)
        child = read(directory / role, "k6-status.json")
        assert child["exitCode"] == 0 and child["captureComplete"] and not child["timedOut"]
        assert child["processId"] == status["processIds"][role]
        assert report["role"] == role
        assert report["plannedStartAtMs"] == schedule["plannedStartAtMs"]
        assert 0 < report["readyAtMs"] < report["plannedStartAtMs"] <= report["startedAtMs"]
        assert 0 <= report["startLagMs"] < 1000
        assert report["joined"] == report["totalConnections"]
        for key in ("errors", "duplicates", "orderViolations", "isolationFailures", "unexpectedCloses",
                    "missingAccepted", "missingResults"):
            assert report[key] == 0, (role, key)
        for room in report["rooms"]:
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
                if role == "normal":
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
