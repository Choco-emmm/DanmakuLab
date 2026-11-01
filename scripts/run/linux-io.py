"""Linux/WSL 小规模网络 I/O 诊断：固定 JAR，串行运行正常/停读的同步/异步四组。

本入口只使用 Python 标准库。它负责启动自己的一台 JVM、采集 Linux 和应用证据、
运行现有慢消费者工具及回收自建进程；不修改 Java、内核参数或已经运行的其他服务。
诊断输出包含失败现场，客户端退出成功不等于已经复现背压或满足投递目标。
"""
import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import sys
import time
from urllib.parse import quote
import zipfile


ROOT = Path(__file__).resolve().parents[2]
CASES = ("direct-normal", "direct-paused", "buffered-normal", "buffered-paused")
# 这些 Gauge 必须存在且收尾为零；事件 Counter 没发生时可以尚未注册，二者不能混淆。
CLEANUP = ("lab.connections", "lab.rooms", "lab.outbound.bytes", "lab.outbound.messages",
           "lab.outbound.slots", "lab.outbound.writes.inflight", "lab.outbound.tasks",
           "lab.outbound.writers.active", "lab.dispatch.bytes", "lab.dispatch.messages",
           "lab.dispatch.rooms", "lab.dispatch.ready")


def save_json(path, value):
    """用 UTF-8 保存结构化证据；所有相对路径都在本轮新建目录内。"""
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def command(arguments, timeout=8):
    """参数数组直接启动进程，不经过 shell；超时与非零退出码也进入诊断记录。"""
    try:
        result = subprocess.run(arguments, capture_output=True, text=True, errors="replace", timeout=timeout)
        return {"arguments": arguments, "exitCode": result.returncode,
                "stdout": result.stdout, "stderr": result.stderr}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"arguments": arguments, "error": str(error)}


def command_failed(result):
    return result.get("exitCode") != 0 or "error" in result


def stop_owned(process):
    """只终止由本脚本 Popen 持有的子进程，先给 JVM 正常清理的时间。"""
    if process is None:
        return None
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=18)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    return process.returncode


def finish_owned(client, server, metrics):
    """独立回收每项自建资源；一个回收错误不能阻止另一台进程退出和最终状态落盘。"""
    result = {"cleanupErrors": []}
    for name, action in (("clientFinalExitCode", lambda: stop_owned(client)),
                         ("metricsClosed", metrics.close),
                         ("serverFinalExitCode", lambda: stop_owned(server))):
        try:
            result[name] = action()
        except Exception as error:
            result["cleanupErrors"].append({"resource": name, "error": f"{type(error).__name__}: {error}"})
    result["serverStillRunning"] = server is not None and server.poll() is None
    result["clientStillRunning"] = client is not None and client.poll() is None
    return result


def process_sample(process):
    """读取指定子进程的 /proc；CPU 为累计秒，RSS 是驻留内存，不是 Java 堆大小。

    stat 的进程名可能有空格，所以先越过最后一个右括号，再按 Linux 字段编号读取。
    CPU 的变化量除以墙钟秒可得到等效逻辑核数，不能直接称整台机器 CPU 百分比。
    """
    if process is None or process.poll() is not None:
        return None
    try:
        directory = Path("/proc") / str(process.pid)
        fields = (directory / "stat").read_text().rsplit(")", 1)[1].split()
        status = dict(line.split(":", 1) for line in (directory / "status").read_text().splitlines() if ":" in line)
        return {"pid": process.pid, "cpuSeconds": (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK"),
                "rssBytes": int(status.get("VmRSS", "0 kB").split()[0]) * 1024,
                "threads": int(status["Threads"]), "state": status["State"].strip()}
    except (OSError, ValueError, KeyError, IndexError) as error:
        return {"pid": process.pid, "error": str(error)}


class Metrics:
    """复用一条 HTTP 连接读取 Actuator，减少采集器自身的建连干扰。

    每个指标有独立读取时刻，不构成全系统原子快照。reason 标签单独保存，避免只看
    聚合值而分不清过期淘汰、网络失败与关停清理；仅访问本轮回环端口。
    """
    def __init__(self, port):
        self.port = port
        self.connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)

    def get(self, path):
        try:
            self.connection.request("GET", path)
            response = self.connection.getresponse()
            body = response.read()
            if response.status != 200:
                raise RuntimeError(f"HTTP {response.status}: {path}")
            return json.loads(body)
        except Exception:
            self.connection.close()
            self.connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=2)
            raise

    def snapshot(self):
        started = time.time()
        names = self.get("/actuator/metrics")["names"]
        selected = [name for name in names if name.startswith("lab.") or name in (
            "jvm.threads.live", "jvm.gc.pause", "process.cpu.usage")]
        values = []
        for name in selected:
            path = "/actuator/metrics/" + quote(name, safe="")
            value = self.get(path)
            for tag in value.get("availableTags", []):
                if tag["tag"] == "reason":
                    value["byReason"] = {reason: self.get(path + "?tag=" + quote("reason:" + reason, safe=""))
                                         for reason in tag["values"]}
            values.append(value)
        values.append(self.get("/actuator/metrics/jvm.memory.used?tag=area%3Aheap"))
        return {"startedAtEpochSeconds": started, "endedAtEpochSeconds": time.time(), "metrics": values}

    def close(self):
        self.connection.close()


def gauge_values(snapshot):
    """只取 Gauge 的 VALUE；缺失保持 None，不把未知值伪装成已清理到零。"""
    metrics = {item["name"]: item for item in snapshot["metrics"]}
    return {name: next((item["value"] for item in metrics.get(name, {}).get("measurements", [])
                       if item["statistic"] == "VALUE"), None) for name in CLEANUP}


def port_available(port):
    """仅检查回环端口是否空闲；被占用时停止，不接管或终止既有进程。"""
    with socket.socket() as listener:
        # 上一组已退出的 TCP 连接可能仍处于 TIME_WAIT。与普通服务器一样允许重用
        # 这类旧地址；仍会拒绝真正处于 LISTEN 的服务，且没有启用 SO_REUSEPORT。
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", port))


def run_case(args, output, case, java, jcmd):
    server_mode, client_mode = case.split("-")
    run = output / case
    run.mkdir()
    server = client = None
    metrics = Metrics(args.port)
    status = {"case": case, "startedAtEpochSeconds": time.time(), "collectionErrors": [], "completed": False}
    server_arguments = [java, "-Xms128m", "-Xmx256m", "-jar", str(args.jar),
                        "--server.address=127.0.0.1", f"--server.port={args.port}",
                        "--lab.cluster.mode=local", "--lab.dispatch.mode=direct",
                        f"--lab.outbound.mode={server_mode}", "--lab.outbound.workers=8"]
    # 执行本轮冻结的脚本副本，避免采集中另一次编辑使四组使用不同负载工具。
    client_arguments = [sys.executable, str(output / "tools/slow-consumer.py"),
                        "--mode", client_mode, "--url", f"ws://127.0.0.1:{args.port}/ws",
                        "--seconds", str(args.seconds), "--rate", str(args.rate),
                        "--viewers", str(args.viewers), "--output", str(run / "client")]
    save_json(run / "commands.json", {"server": server_arguments, "client": client_arguments,
                                       "ss": ["ss", "-tinmp", f"( sport = :{args.port} or dport = :{args.port} )"]})
    with (run / "server.txt").open("w") as server_log, (run / "client.txt").open("w") as client_log, \
            (run / "samples.jsonl").open("w") as samples:
        try:
            port_available(args.port)
            server = subprocess.Popen(server_arguments, cwd=ROOT, stdout=server_log, stderr=subprocess.STDOUT)
            status["serverPid"] = server.pid
            ready_until = time.monotonic() + 45
            while True:
                if server.poll() is not None:
                    raise RuntimeError(f"JVM 在就绪前退出: {server.returncode}")
                try:
                    if metrics.get("/actuator/health")["status"] == "UP":
                        break
                except (OSError, RuntimeError, http.client.HTTPException):
                    pass
                if time.monotonic() > ready_until:
                    raise TimeoutError("JVM 45 秒内未就绪")
                time.sleep(.2)
            # 先记录 JVM 自报版本/参数，确保没有把 Windows java.exe 或另一台服务当成 Linux JVM。
            identity = {name: command([jcmd, str(server.pid), name])
                        for name in ("VM.version", "VM.command_line", "VM.flags")}
            save_json(run / "jvm-identity.json", identity)
            if any(command_failed(result) for result in identity.values()):
                raise RuntimeError("jcmd 无法读取本轮 JVM 身份，保留日志后停止")
            listener = command(["ss", "-ltnp", f"sport = :{args.port}"])
            save_json(run / "listener-identity.json", listener)
            if command_failed(listener) or f"pid={server.pid}," not in listener.get("stdout", ""):
                raise RuntimeError("实验端口没有由本轮 JVM 持有，停止向该端口发送负载")
            before = metrics.snapshot()
            save_json(run / "before.json", before)
            if any(value != 0 for value in gauge_values(before).values()):
                raise RuntimeError("全新 JVM 的连接/预算 Gauge 缺失或未归零")
            modes = {item["name"]: item["measurements"][0]["value"] for item in before["metrics"]
                     if item["name"] in ("lab.outbound.mode.buffered", "lab.dispatch.mode.fair")}
            if modes != {"lab.outbound.mode.buffered": int(server_mode == "buffered"), "lab.dispatch.mode.fair": 0}:
                raise RuntimeError(f"实际输出/调度模式与对照计划不符：{modes}")
            client = subprocess.Popen(client_arguments, cwd=ROOT, stdout=client_log, stderr=subprocess.STDOUT,
                                      env={**os.environ, "PYTHONUNBUFFERED": "1", "PYTHONUTF8": "1"})
            status["clientPid"] = client.pid
            started = time.monotonic()
            next_sample, next_threads = 0.0, min(8.0, args.seconds / 2)
            number = 0
            print(f"Started {case}: JVM={server.pid}, client={client.pid}", flush=True)
            while client.poll() is None:
                elapsed = time.monotonic() - started
                if elapsed > args.seconds + 60:
                    raise TimeoutError("慢消费者工具超出本轮有限执行窗口")
                if server.poll() is not None:
                    raise RuntimeError("采样期间 JVM 异常退出")
                if elapsed >= next_sample:
                    row = {"atEpochSeconds": time.time(), "elapsedSeconds": elapsed,
                           "server": process_sample(server), "client": process_sample(client)}
                    for side in ("server", "client"):
                        if isinstance(row[side], dict) and "error" in row[side]:
                            status["collectionErrors"].append({"elapsed": elapsed,
                                                               "error": f"{side} /proc: {row[side]['error']}"})
                    try:
                        row["application"] = metrics.snapshot()
                    except Exception as error:
                        row["error"] = str(error)
                        status["collectionErrors"].append({"elapsed": elapsed, "error": str(error)})
                    row["ss"] = command(["ss", "-tinmp", f"( sport = :{args.port} or dport = :{args.port} )"])
                    if command_failed(row["ss"]):
                        status["collectionErrors"].append({"elapsed": elapsed, "error": "ss collection failed"})
                    samples.write(json.dumps(row, ensure_ascii=False) + "\n")
                    samples.flush()
                    next_sample = elapsed + 1
                if elapsed >= next_threads:
                    # Thread.print 用于判断网络等待和锁竞争；top 的首帧 CPU 不能当一秒窗口占用。
                    label = f"{number:02d}-{elapsed:.1f}s"
                    threads = {"atEpochSeconds": time.time(),
                               "jcmd": command([jcmd, str(server.pid), "Thread.print", "-l"]),
                               "top": command(["top", "-b", "-H", "-n", "1", "-p", str(server.pid)])}
                    save_json(run / f"threads-{label}.json", threads)
                    if command_failed(threads["jcmd"]) or command_failed(threads["top"]):
                        status["collectionErrors"].append({"elapsed": elapsed, "error": "thread evidence collection failed"})
                    number += 1
                    next_threads = elapsed + 20
                time.sleep(.1)
            status["clientExitCode"] = client.returncode
            if client.returncode != 0:
                raise RuntimeError(f"慢消费者工具退出码 {client.returncode}")
            summary = json.loads((run / "client/summary.json").read_text())
            status["clientSummary"] = {key: summary.get(key) for key in (
                "normalReceived", "normalExpected", "accepted", "inputScheduleCompleted", "maxSendLagMs",
                "normalLatencyMs", "duplicates", "orderViolations", "failures", "rejections")}
            status["normalDeliveryComplete"] = summary["normalReceived"] == summary["normalExpected"]
            # 收尾需要连接、两层队列、在途和名额全归零；即使投递失败也保存完整清理证据。
            cleanup_until = time.monotonic() + 15
            while True:
                after = metrics.snapshot()
                values = gauge_values(after)
                if all(value == 0 for value in values.values()) or time.monotonic() >= cleanup_until:
                    break
                time.sleep(.5)
            save_json(run / "after.json", after)
            status["cleanupGauges"] = values
            status["cleanupPassed"] = all(value == 0 for value in values.values())
            status["completed"] = status["cleanupPassed"] and not status["collectionErrors"]
            print(f"Finished {case}: normal={summary['normalReceived']}/{summary['normalExpected']}, "
                  f"p99={summary['normalLatencyMs']['p99']}ms, cleanup={status['cleanupPassed']}", flush=True)
        except Exception as error:
            status["error"] = f"{type(error).__name__}: {error}"
            print(f"Failed {case}: {status['error']}", flush=True)
        finally:
            status.update(finish_owned(client, server, metrics))
            status["endedAtEpochSeconds"] = time.time()
            if status["cleanupErrors"] or status["serverStillRunning"] or status["clientStillRunning"]:
                status["completed"] = False
            status["ssAfterStop"] = command(["ss", "-ltn", f"sport = :{args.port}"])
            save_json(run / "status.json", status)
    return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--seconds", type=int, default=90)
    parser.add_argument("--rate", type=int, default=40)
    parser.add_argument("--viewers", type=int, default=10)
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--cases", nargs="+", choices=CASES, default=list(CASES))
    args = parser.parse_args()
    if platform.system() != "Linux":
        parser.error("本入口需要 Linux；Windows 请从 WSL Ubuntu 内运行")
    if not (1 <= args.seconds <= 180 and 1 <= args.rate <= 40 and 10 <= args.viewers <= 100 and 1024 <= args.port <= 65535):
        parser.error("seconds=1..180，rate=1..40，viewers=10..100，port=1024..65535")
    args.jar = args.jar.resolve(strict=True)
    args.output = args.output.resolve()
    java, jcmd = (str(args.java_home.resolve(strict=True) / "bin" / name) for name in ("java", "jcmd"))
    for tool in (java, jcmd, "ss", "top"):
        if shutil.which(tool) is None:
            parser.error(f"缺少工具：{tool}")
    # 输出目录不能复用；预先检查端口，不向用户占用的服务发送实验流量。
    port_available(args.port)
    args.output.mkdir(parents=True, exist_ok=False)
    output = args.output
    initial_hash = digest(args.jar)
    tools = output / "tools"
    tools.mkdir()
    for source in (Path(__file__).resolve(), ROOT / "scripts/load/slow-consumer.py"):
        shutil.copy2(source, tools / source.name)
    with zipfile.ZipFile(args.jar) as archive:
        (output / "jar-application.yml").write_bytes(archive.read("BOOT-INF/classes/application.yml"))
    save_json(output / "environment.json", {
        "createdAtUtc": datetime.now(timezone.utc).isoformat(), "platform": platform.platform(),
        "osRelease": Path("/etc/os-release").read_text(), "procVersion": Path("/proc/version").read_text(),
        "python": sys.version, "java": command([java, "-version"]), "jcmd": jcmd,
        "cpu": command(["lscpu"]), "memory": command(["free", "-b"]),
        "tcp": command(["sysctl", "net.ipv4.tcp_wmem", "net.ipv4.tcp_rmem", "net.core.wmem_default", "net.core.rmem_default"]),
        "jar": str(args.jar), "jarSha256": initial_hash,
        "configuration": {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()},
        "network": "Same WSL2 Linux instance, IPv4 loopback; shared Windows physical host; no remote/public-network claim.",
        "timing": "No excluded warm-up; per case fresh JVM; client 7-second drain; samples approximately 1s, thread dumps approximately 20s.",
        "sourceSha256": {str(path.relative_to(ROOT)): digest(path) for path in (
            Path(__file__).resolve(), ROOT / "scripts/load/slow-consumer.py", ROOT / "pom.xml")}})
    statuses = []
    for case in args.cases:
        statuses.append(run_case(args, output, case, java, jcmd))
        if not statuses[-1]["completed"]:
            break
    jar_unchanged = digest(args.jar) == initial_hash
    save_json(output / "batch-status.json", {"cases": statuses, "jarUnchanged": jar_unchanged,
              "allCasesCompleted": jar_unchanged and len(statuses) == len(args.cases) and all(item["completed"] for item in statuses),
              "note": "Completed means collection and cleanup, not proof of backpressure or performance success. Inspect client counters and Linux evidence."})
    return 0 if jar_unchanged and len(statuses) == len(args.cases) and all(item["completed"] for item in statuses) else 1


if __name__ == "__main__":
    raise SystemExit(main())
