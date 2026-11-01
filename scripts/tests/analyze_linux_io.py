"""汇总 Linux 诊断原始证据；输出可复核 JSON 和图，不自动把相关性判为根因。

只读取四组已有文件。按负载工具记录的探针端口匹配 ss 双向连接，排除 Actuator 与
正常观众；保留每个样本时刻。matplotlib 仅在请求 --plot 时导入，服务无需此依赖。
"""
import argparse
import hashlib
import json
from pathlib import Path
import re


def metric(snapshot, name, statistic=None):
    for item in snapshot.get("metrics", []):
        if item["name"] == name:
            return next((entry["value"] for entry in item["measurements"]
                         if statistic is None or entry["statistic"] == statistic), None)
    return None


def sockets(text):
    """ss 一行连接头配合后面的缩进行；同时兼容 IPv4 与 IPv4-mapped IPv6 地址。"""
    parsed = []
    for line in text.splitlines():
        match = re.match(r"^(\S+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(\S+)\s*(.*)$", line)
        if match:
            parsed.append({"state": match[1], "recvQ": int(match[2]), "sendQ": int(match[3]),
                           "local": match[4], "peer": match[5], "process": match[6], "detail": ""})
        elif parsed and line[:1].isspace():
            parsed[-1]["detail"] += line.strip() + " "
    return parsed


def port(endpoint):
    return int(endpoint.rsplit(":", 1)[1])


def first_time(rows, key, predicate):
    return next((row["elapsedSeconds"] for row in rows if row.get(key) is not None and predicate(row[key])), None)


def summarize(run):
    summary = json.loads((run / "client/summary.json").read_text(encoding="utf-8"))
    status = json.loads((run / "status.json").read_text(encoding="utf-8"))
    probe_port = summary["clientEndpoints"]["probe"]["local"][1]
    server_port = summary["clientEndpoints"]["probe"]["peer"][1]
    rows = []
    ownership_mismatches = []
    unowned_sockets = []
    for line in (run / "samples.jsonl").read_text(encoding="utf-8").splitlines():
        sample = json.loads(line)
        tcp = sockets(sample["ss"].get("stdout", ""))
        server = next((entry for entry in tcp if port(entry["local"]) == server_port
                       and port(entry["peer"]) == probe_port), None)
        client = next((entry for entry in tcp if port(entry["local"]) == probe_port
                       and port(entry["peer"]) == server_port), None)
        if server:
            # 应用关闭 fd 后，内核仍可保留 FIN-WAIT 等 TCP 状态，此时 ss 没有 users/pid。
            # 无所有者与实际出现另一个 PID 是两种证据，不能合并成“连到了别的服务”。
            if "pid=" not in server["process"]:
                unowned_sockets.append(sample["elapsedSeconds"])
            elif f"pid={status['serverPid']}," not in server["process"]:
                ownership_mismatches.append(sample["elapsedSeconds"])
        application = sample.get("application", {})
        row = {"elapsedSeconds": sample["elapsedSeconds"], "atEpochSeconds": sample["atEpochSeconds"],
               "probeServerSendQ": server["sendQ"] if server else None,
               "probeClientRecvQ": client["recvQ"] if client else None,
               "probeServerSocket": server, "probeClientSocket": client,
               "outboundBytes": metric(application, "lab.outbound.bytes"),
               "outboundMessages": metric(application, "lab.outbound.messages"),
               "inflight": metric(application, "lab.outbound.writes.inflight"),
               "writersActive": metric(application, "lab.outbound.writers.active"),
               "dropped": metric(application, "lab.outbound.dropped") or 0,
               "closed": metric(application, "lab.outbound.closed") or 0,
               "writeFailed": metric(application, "lab.writes.failed") or 0,
               "server": sample["server"], "client": sample["client"]}
        if server:
            window = re.search(r"\bsnd_wnd:(\d+)", server["detail"])
            row["probeSndWnd"] = int(window[1]) if window else None
        rows.append(row)
    threads = []
    for path in sorted(run.glob("threads-*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        text = document["jcmd"].get("stdout", "")
        blocks = re.split(r"(?m)(?=^\")", text)
        # 输出包含具体业务发送路径的线程块，用于人工核对状态和房间锁，而非只搜索 WAITING 单词。
        selected = [block for block in blocks if "ClientConnection.writeNow" in block
                    or "DirectBroadcaster.broadcast" in block or "ClientConnection.writeAsync" in block]
        threads.append({"file": path.name, "atEpochSeconds": document["atEpochSeconds"],
                        "sendingThreadBlocks": selected})
    values = lambda key: [row[key] for row in rows if row.get(key) is not None]
    after = json.loads((run / "after.json").read_text(encoding="utf-8"))
    events = {item["name"]: item for item in after["metrics"] if item["name"] in (
        "lab.outbound.dropped", "lab.outbound.discarded", "lab.outbound.closed", "lab.writes.failed", "lab.messages.rejected")}
    return {"case": run.name, "clientSummary": summary, "completed": status["completed"],
            "collectionErrors": status["collectionErrors"], "cleanupPassed": status["cleanupPassed"],
            "serverPid": status["serverPid"], "probeLocalPort": probe_port, "serverPort": server_port,
            "probeSocketPidMismatches": ownership_mismatches,
            "probeSocketWithoutOwnerAtSeconds": unowned_sockets,
            "peakSampled": {key: max(values(key), default=None) for key in (
                "probeServerSendQ", "probeClientRecvQ", "outboundBytes", "outboundMessages", "inflight", "writersActive")},
            "firstZeroSendWindowAtSeconds": first_time(rows, "probeSndWnd", lambda value: value == 0),
            "firstOutputDropAtSeconds": first_time(rows, "dropped", lambda value: value > 0),
            "eventsAfter": events, "threads": threads, "timeline": rows}


def plot(result, output):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    figure, axes = plt.subplots(3, 2, figsize=(12, 9), sharex="col", constrained_layout=True)
    for item in result["cases"]:
        column = 0 if item["case"].startswith("direct") else 1
        style = "-" if item["case"].endswith("paused") else "--"
        rows = item["timeline"]
        x = [row["elapsedSeconds"] for row in rows]
        # socket 消失或采集缺失时留空，不能画成“已测到零积压”。
        axes[0, column].plot(x, [None if row["probeServerSendQ"] is None else row["probeServerSendQ"] / 1024
                                for row in rows], style, label=item["case"])
        axes[1, column].plot(x, [None if row["outboundBytes"] is None else row["outboundBytes"] / 1024
                                for row in rows], style, label=item["case"])
        axes[2, column].plot(x, [row["inflight"] for row in rows], style, label=item["case"])
    for column, title in enumerate(("Synchronous output", "Asynchronous buffered output")):
        axes[0, column].set_title(title)
        axes[2, column].set_xlabel("Seconds since load process started")
        for axis in axes[:, column]:
            axis.grid(alpha=.2)
            axis.legend(fontsize=8)
    for row, label in enumerate(("Probe TCP Send-Q (KiB)", "Application output budget (KiB)", "Unsettled application writes")):
        axes[row, 0].set_ylabel(label)
    figure.suptitle("WSL2 / Temurin 21: 10 normal readers + 1 probe, 40 inputs/s, 90s\n"
                   "One run per case; sampled values; shared physical host")
    figure.savefig(output, dpi=150)
    plt.close(figure)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--plot", action="store_true")
    args = parser.parse_args()
    result = {"cases": [summarize(args.directory / case) for case in (
        "direct-normal", "direct-paused", "buffered-normal", "buffered-paused")]}
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    (args.directory / "analysis.json").write_text(text, encoding="utf-8")
    if args.plot:
        plot(result, args.directory / "linux-io.png")
    # 在报告写完之后生成单独的原始证据索引；不把索引自己的哈希递归写进索引。
    files = [path for path in args.directory.rglob("*") if path.is_file()
             and path.name not in ("evidence-sha256.json", "analysis.json", "linux-io.png", "notes.md")]
    manifest = []
    for path in sorted(files):
        with path.open("rb") as stream:
            checksum = hashlib.file_digest(stream, "sha256").hexdigest()
        manifest.append({"path": str(path.relative_to(args.directory)), "bytes": path.stat().st_size, "sha256": checksum})
    (args.directory / "evidence-sha256.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    for item in result["cases"]:
        client = item["clientSummary"]
        print(item["case"], f"received={client['normalReceived']}/{client['normalExpected']}",
              f"p99={client['normalLatencyMs']['p99']}ms", item["peakSampled"],
              f"zeroWindowAt={item['firstZeroSendWindowAtSeconds']}", flush=True)


if __name__ == "__main__":
    main()
