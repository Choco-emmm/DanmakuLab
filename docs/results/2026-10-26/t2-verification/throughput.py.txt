"""T2单房间吞吐阶梯：四个独立Python进程分担同一房间观众，默认5/20/50/100条输入每秒。

只复用longrun.py的WebSocket编解码、连接和固定空间直方图，不改变历史T3/T5工具。
所有观众（含发送者）始终留在分母；每个进程逐观众去重，父进程逐输入核对接受与实际接收。
这是测量工具，不实现服务器限流。房间额度必须由启动命令显式配置并由PowerShell入口核对。
"""
import argparse
import asyncio
from array import array
from collections import Counter
from contextlib import redirect_stderr, redirect_stdout
import json
import multiprocessing as mp
import os
from pathlib import Path
import queue
import time
from types import SimpleNamespace
from urllib.parse import urlparse

from longrun import Histogram, Peer


def save(path, data):
    """一个进程只写自己的文件；先写临时文件再替换，采集器不会读到半份PID清单。"""
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


class Plan:
    """把每档预热/测量时间映射成有界消息编号；收到晚到消息仍按输入编号归属原阶段。"""
    def __init__(self, rates, seconds, warmup):
        self.phases = []
        self.total = 0
        for index, rate in enumerate(rates):
            self.phases.append(dict(index=index, rate=rate, offset=self.total,
                                    warmupMessages=rate * warmup, measuredMessages=rate * seconds,
                                    startSeconds=index * (warmup + seconds)))
            self.total += rate * (warmup + seconds)
        self.seconds, self.warmup = seconds, warmup
        self.duration = len(rates) * (seconds + warmup)
        self.phase_by_input = bytearray(self.total)
        self.measured = bytearray(self.total)
        for phase in self.phases:
            begin = phase['offset']
            end = begin + phase['warmupMessages'] + phase['measuredMessages']
            self.phase_by_input[begin:end] = bytes([phase['index']]) * (end - begin)
            self.measured[begin + phase['warmupMessages']:end] = bytes([1]) * phase['measuredMessages']


def message(room, index, timestamp):
    """完整输入JSON固定256个UTF-8字节；昵称等服务端添加字段不算在这256字节里。"""
    event = dict(type='send', clientMessageId=f'{room}:{index:06d}', text='', clientSentAt=timestamp)
    padding = 256 - len(json.dumps(event, separators=(',', ':')).encode())
    if not 1 <= padding <= 200:
        raise ValueError('无法构造符合正文上限的256字节输入')
    event['text'] = 'x' * padding
    return event


def histogram_data(histogram):
    """只写非零桶，仍保留原始整数计数、真实总和与溢出，不保存每次接收的独立对象。"""
    return dict(bins=[[value, count] for value, count in enumerate(histogram.bins) if count],
                count=histogram.count, total=histogram.total, maximum=histogram.maximum, overflow=histogram.overflow)


def merge_histograms(parts):
    """合并各进程的桶再求分位数；绝不平均四个进程的P99。"""
    result = Histogram()
    for part in parts:
        assert sum(count for _, count in part['bins']) == part['count']
        for value, count in part['bins']:
            assert type(value) is int and 0 <= value <= 120000 and type(count) is int and count > 0
            result.bins[value] += count
        result.count += part['count']
        result.total += part['total']
        result.maximum = max(result.maximum, part['maximum'])
        result.overflow += part['overflow']
    return result.report()


class WorkerState:
    """每进程一个事件循环管理本分片；业务统计只在本进程更新，不跨进程依赖GIL或无锁共享数组。"""
    def __init__(self, args, worker, begin, end):
        self.args, self.worker, self.begin, self.end = args, worker, begin, end
        self.plan = Plan(args.rates, args.seconds, args.warmup_seconds)
        self.room, self.instance, self.run_id = args.room, None, None
        self.joined = self.sent = self.duplicates = self.order_errors = self.protocol_errors = 0
        self.measuring = self.closing = False
        self.started_at = self.ready_at = 0
        self.seen = [bytearray(self.plan.total) for _ in range(end - begin)]
        self.last_sequence = array('Q', [0]) * (end - begin)
        self.sequence_by_input = array('Q', [0]) * self.plan.total
        self.input_by_sequence = array('i', [-1]) * (self.plan.total + 1)
        self.timestamps = array('q', [0]) * self.plan.total
        self.receipts = array('H', [0]) * self.plan.total
        self.status = bytearray(self.plan.total)
        # 每阶段分别保存预热/测量拒绝，不能把预热拒绝混进测量期的拒绝原因分母。
        self.rejection_reasons = [Counter() for _ in range(len(args.rates) * 2)]
        self.text_by_digits = {}
        self.per_viewer = [[0] * (len(args.rates) * 2) for _ in range(end - begin)]
        self.latency = [Histogram() for _ in args.rates]
        self.max_lag = [0.0] * len(args.rates)
        self.measure_durations = [0.0] * len(args.rates)
        self.failure_counts = Counter()
        self.failure_details = []
        self.phase_failures = [0] * len(args.rates)
        self.current_phase = 0
        self.start_epoch = 0.0

    def failure(self, peer, operation, error):
        self.failure_counts[operation] += 1
        phase = min(len(self.args.rates) - 1, max(0, int((time.time() - self.start_epoch) //
                    (self.args.seconds + self.args.warmup_seconds)))) if self.start_epoch else 0
        self.phase_failures[phase] += 1
        if len(self.failure_details) < 30:
            self.failure_details.append(dict(viewer=peer.index, operation=operation,
                error=f'{type(error).__name__}:{error}', at=time.time()))

    def event(self, peer, event):
        """校验真实收帧；接收进程没有发送时钟的共享数组，最后由父进程与发送记录逐条对照。"""
        if event.get('type') == 'pong':
            return
        identity = event.get('clientMessageId', '')
        prefix = self.room + ':'
        if not isinstance(identity, str) or not identity.startswith(prefix):
            raise ValueError('未知消息ID或串房')
        index = int(identity[len(prefix):])
        if not 0 <= index < self.plan.total or identity != f'{self.room}:{index:06d}':
            raise ValueError('消息ID越界或格式非法')
        phase = self.plan.phase_by_input[index]
        if event.get('type') == 'send_result':
            # 所有业务输入由worker0的观众轮转发送，其他进程不能冒充该发送者的确认。
            if self.worker != 0 or peer.index != index % (self.end - self.begin) or self.status[index]:
                raise ValueError('错误发送者或重复确认')
            if event.get('status') == 'accepted':
                self.status[index] = 1
            elif event.get('status') == 'rejected':
                self.status[index] = 2
                self.rejection_reasons[phase * 2 + self.plan.measured[index]][event.get('reason', 'unknown')] += 1
            else:
                raise ValueError('未知发送结果')
            return
        timestamp = event.get('clientSentAt')
        if (event.get('type') != 'danmaku' or event.get('roomId') != self.room
                or event.get('roomInstanceId') != self.instance or type(timestamp) is not int or not 0 <= timestamp <= 9223372036854775807):
            raise ValueError('广播类型、房间或时间戳错误')
        # 相同房间、六位ID和相同时间戳位数的正文长度相同；只缓存最多19种位数，
        # 避免为每次观众接收重新json.dumps，先把压测器自身的重复编码开销控制住。
        digits = len(str(timestamp))
        if digits not in self.text_by_digits:
            self.text_by_digits[digits] = message(self.room, index, timestamp)['text']
        if event.get('text') != self.text_by_digits[digits]:
            raise ValueError('广播正文错误')
        run_id = event.get('runId')
        if not run_id or self.run_id not in (None, run_id):
            raise ValueError('服务运行标识变化')
        self.run_id = run_id
        if self.timestamps[index] not in (0, timestamp):
            raise ValueError('同一输入的观众时间戳不一致')
        self.timestamps[index] = timestamp
        local = peer.index - self.begin
        if self.seen[local][index]:
            self.duplicates += 1
            return
        sequence = event.get('roomSeq')
        if type(sequence) is not int or not 1 <= sequence <= self.plan.total:
            raise ValueError('非法房间序号')
        if (sequence <= self.last_sequence[local] or self.sequence_by_input[index] not in (0, sequence)
                or self.input_by_sequence[sequence] not in (-1, index)):
            self.order_errors += 1
        self.last_sequence[local] = self.sequence_by_input[index] = sequence
        self.input_by_sequence[sequence] = index
        self.seen[local][index] = 1
        self.receipts[index] += 1
        measured = self.plan.measured[index]
        self.per_viewer[local][phase * 2 + measured] += 1
        if measured and peer.index % self.args.latency_sample_every == 0:
            self.latency[phase].add(time.time_ns() // 1000000 - timestamp)

    def report(self, fatal):
        return dict(worker=self.worker, pid=os.getpid(), begin=self.begin, end=self.end,
            room=self.room, instance=self.instance, runId=self.run_id, joined=self.joined,
            readyAt=self.ready_at, startedAt=self.started_at, sent=self.sent,
            status=list(self.status) if self.worker == 0 else None,
            timestamps=list(self.timestamps), sequenceByInput=list(self.sequence_by_input), receipts=list(self.receipts),
            perViewer=self.per_viewer, histograms=[histogram_data(h) for h in self.latency],
            maxSendLagMs=self.max_lag, measureDurations=self.measure_durations,
            rejectionReasons=[dict(value) for value in self.rejection_reasons],
            failures=dict(self.failure_counts), phaseFailures=self.phase_failures,
            failureDetails=self.failure_details, duplicates=self.duplicates,
            orderViolations=self.order_errors, protocolErrors=self.protocol_errors, fatal=fatal)


async def run_worker(args, worker, begin, end, output, ready, gate, start_at, cancel):
    """先全部join，再等父进程共同起点；读取循环、心跳与发送计划各自协作，所有等待有总截止。"""
    state = WorkerState(args, worker, begin, end)
    peers = [Peer(state, index) for index in range(begin, end)]
    tasks, open_tasks, fatal = [], [], None
    try:
        semaphore = asyncio.Semaphore(20)
        async def open_one(peer):
            async with semaphore:
                await peer.open()
        open_tasks = [asyncio.create_task(open_one(peer)) for peer in peers]
        await asyncio.wait_for(asyncio.gather(*open_tasks), 60)
        state.ready_at = time.time()
        ready.put(dict(worker=worker, at=state.ready_at, instance=state.instance))

        async def heartbeat():
            while True:
                await asyncio.sleep(15)
                for peer in peers:
                    if peer.alive:
                        try:
                            await peer.send(dict(type='ping'))
                        except Exception as error:
                            peer.alive = False
                            state.failure(peer, 'heartbeat', error)

        async def progress():
            while True:
                with (output / 'progress.jsonl').open('a', encoding='utf-8') as stream:
                    stream.write(json.dumps(dict(at=time.time(), worker=worker, sent=state.sent,
                        received=sum(state.receipts), failures=dict(state.failure_counts))) + '\n')
                await asyncio.sleep(5)

        tasks = [asyncio.create_task(heartbeat()), asyncio.create_task(progress())]
        waiting_deadline = time.monotonic() + 65
        while not gate.is_set():
            if cancel.is_set() or time.monotonic() >= waiting_deadline:
                raise TimeoutError('等待其他观众就绪超时或被取消')
            await asyncio.sleep(.02)
        # Value的锁保护共同起点的跨进程读取，不用未经同步的共享浮点变量。
        with start_at.get_lock():
            epoch = start_at.value
        state.start_epoch = epoch
        origin = time.monotonic() + epoch - time.time()
        await asyncio.sleep(max(0, origin - time.monotonic()))
        state.started_at, state.measuring = time.time(), True
        if worker == 0:
            for phase in state.plan.phases:
                p, rate = phase['index'], phase['rate']
                state.current_phase = p
                count = phase['warmupMessages'] + phase['measuredMessages']
                measured_start = None
                for number in range(count):
                    if cancel.is_set():
                        raise RuntimeError('父进程取消实验')
                    index = phase['offset'] + number
                    target = origin + phase['startSeconds'] + number / rate
                    await asyncio.sleep(max(0, target - time.monotonic()))
                    if number == phase['warmupMessages']:
                        measured_start = time.monotonic()
                    if number >= phase['warmupMessages']:
                        state.max_lag[p] = max(state.max_lag[p], (time.monotonic() - target) * 1000)
                    event = message(state.room, index, time.time_ns() // 1000000)
                    state.timestamps[index] = event['clientSentAt']
                    await peers[index % len(peers)].send(event)
                    state.sent += 1
                await asyncio.sleep(max(0, origin + phase['startSeconds'] + args.warmup_seconds + args.seconds - time.monotonic()))
                state.measure_durations[p] = time.monotonic() - measured_start
        else:
            # 所有观众按共同结束时间停止，而不是某进程先收够了就删除它，固定分母不缩小。
            await asyncio.sleep(max(0, origin + state.plan.duration - time.monotonic()))
        await asyncio.sleep(max(0, origin + state.plan.duration + args.drain_seconds - time.monotonic()))
    except Exception as error:
        fatal = f'{type(error).__name__}:{error}'
    finally:
        save(output / 'summary.json', state.report(fatal))
        state.closing = True
        for task in open_tasks + tasks:
            task.cancel()
        for peer in peers:
            peer.close()
        await asyncio.gather(*open_tasks, *tasks, *(peer.task for peer in peers if peer.task), return_exceptions=True)
    return 1 if fatal else 0


def worker_entry(args, worker, begin, end, output, ready, gate, start_at, cancel):
    """每个OS进程独立输出；父进程即使失败，也能保留已落盘的客户端证据。"""
    with (output / 'stdout.txt').open('w', encoding='utf-8') as stdout, (output / 'stderr.txt').open('w', encoding='utf-8') as stderr:
        with redirect_stdout(stdout), redirect_stderr(stderr):
            raise SystemExit(asyncio.run(run_worker(args, worker, begin, end, output, ready, gate, start_at, cancel)))


def summarize(args, reports, processes, epoch, fatal):
    """按输入逐条交叉核对时间戳、序号与接受结果，再合并整数直方图，不用服务端send当接收量。"""
    plan = Plan(args.rates, args.seconds, args.warmup_seconds)
    sender = next((report for report in reports if report['worker'] == 0), None)
    if sender is None or len(reports) != args.workers:
        return dict(toolValid=False, allPhasesSloPassed=False, fatal=fatal or '缺少工作进程报告', phases=[], processes=processes)
    metadata_ok = len({r['instance'] for r in reports}) == 1 and len({r['runId'] for r in reports if r['runId']}) == 1
    for index in range(plan.total):
        sequences = {r['sequenceByInput'][index] for r in reports if r['sequenceByInput'][index]}
        metadata_ok &= len(sequences) <= 1
        for report in reports:
            if report['receipts'][index]:
                metadata_ok &= (sender['status'][index] == 1 and report['timestamps'][index] == sender['timestamps'][index])
    joined = sum(r['joined'] for r in reports)
    startup_ok = all(epoch and 0 < r['readyAt'] < epoch and 0 <= (r['startedAt'] - epoch) * 1000 < 1000 for r in reports)
    protocol_ok = all(r['duplicates'] == r['orderViolations'] == r['protocolErrors'] == 0 for r in reports)
    process_ok = all(p['exitCode'] == 0 for p in processes) and all(r['fatal'] is None for r in reports)
    common = joined == args.connections and startup_ok and protocol_ok and process_ok and metadata_ok and fatal is None
    phases = []
    for phase in plan.phases:
        p = phase['index']
        lo = phase['offset'] + phase['warmupMessages']
        hi = lo + phase['measuredMessages']
        accepted = sender['status'][lo:hi].count(1)
        rejected = sender['status'][lo:hi].count(2)
        sent = min(phase['measuredMessages'], max(0, sender['sent'] - lo))
        received = sum(sum(r['receipts'][lo:hi]) for r in reports)
        warm_received = sum(sum(r['receipts'][phase['offset']:lo]) for r in reports)
        warm_accepted = sender['status'][phase['offset']:lo].count(1)
        counts = [row[p * 2 + 1] for r in reports for row in r['perViewer']]
        duration = sender['measureDurations'][p]
        actual_rate = sent / duration if duration else 0
        latency = merge_histograms([r['histograms'][p] for r in reports])
        failures = sum(r['phaseFailures'][p] for r in reports)
        tool_valid = (common and sent == phase['measuredMessages'] and accepted + rejected == sent
                      and args.rates[p] * .95 <= actual_rate <= args.rates[p] * 1.05 and sender['maxSendLagMs'][p] < 1000)
        complete = received == accepted * args.connections and all(value == accepted for value in counts)
        slo = (tool_valid and complete and accepted == phase['measuredMessages'] and not failures
               and warm_received == warm_accepted * args.connections and warm_accepted == phase['warmupMessages']
               and latency['p95'] is not None and latency['p95'] < 200 and latency['p99'] < 500
               and latency['overflowAbove120000ms'] == 0)
        phases.append(dict(rate=phase['rate'], sent=sent, accepted=accepted, rejected=rejected,
            rejectionReasons=sender['rejectionReasons'][p * 2 + 1],
            warmupRejectionReasons=sender['rejectionReasons'][p * 2], warmupAccepted=warm_accepted,
            warmupReceived=warm_received, allInputDeliveryDemand=phase['measuredMessages'] * args.connections,
            acceptedDeliveryDemand=accepted * args.connections, received=received,
            missingAccepted=accepted * args.connections - received, perViewerReceived=counts,
            actualMeasureSeconds=duration, actualInputPerSecond=actual_rate, maxSendLagMs=sender['maxSendLagMs'][p],
            latencyMs=latency, readerFailures=failures, toolValid=tool_valid,
            acceptedComplete=complete, candidateSloPassed=slo))
    return dict(scenario='T2', connections=args.connections, workers=args.workers, rates=args.rates,
        seconds=args.seconds, warmupSeconds=args.warmup_seconds, requestBytes=256, joined=joined,
        plannedIncludingWarmup=plan.total, sentIncludingWarmup=sender['sent'],
        acceptedIncludingWarmup=sender['status'].count(1), rejectedIncludingWarmup=sender['status'].count(2),
        receivedIncludingWarmup=sum(sum(r['receipts']) for r in reports),
        expectedAcceptedIncludingWarmup=sender['status'].count(1) * args.connections,
        duplicates=sum(r['duplicates'] for r in reports), orderViolations=sum(r['orderViolations'] for r in reports),
        protocolErrors=sum(r['protocolErrors'] for r in reports), metadataConsistent=bool(metadata_ok),
        startupAligned=startup_ok, startAt=epoch, processes=processes, fatal=fatal, phases=phases,
        toolValid=all(p['toolValid'] for p in phases), allPhasesSloPassed=all(p['candidateSloPassed'] for p in phases),
        note='固定全部观众分母；仅worker0轮转发输入。接收分片逐输入对照后合并直方图。toolValid不是性能全部达标，拒绝/丢失/断连/SLO另报。')


def experiment(args, output):
    """父进程只协调就绪和共同起点；总截止后终止本轮派生进程，不接管或关闭用户服务。"""
    context = mp.get_context('spawn')
    ready, gate, cancel = context.Queue(), context.Event(), context.Event()
    start_at = context.Value('d', 0.0)
    children, fatal, epoch = [], None, 0.0
    try:
        for worker in range(args.workers):
            folder = output / f'worker-{worker}'
            folder.mkdir()
            begin, end = worker * args.connections // args.workers, (worker + 1) * args.connections // args.workers
            child = context.Process(target=worker_entry, args=(args, worker, begin, end, folder, ready, gate, start_at, cancel))
            child.start()
            children.append(child)
        save(output / 'processes.json', dict(parent=os.getpid(), workers=[c.pid for c in children]))
        deadline, prepared = time.monotonic() + 65, {}
        while len(prepared) < args.workers:
            if time.monotonic() >= deadline or any(c.exitcode is not None for c in children):
                raise TimeoutError('工作进程未在共同起点前全部就绪')
            try:
                item = ready.get(timeout=.2)
                prepared[item['worker']] = item
            except queue.Empty:
                pass
        if len({item['instance'] for item in prepared.values()}) != 1:
            raise ValueError('各工作进程没有加入同一房间实例')
        epoch = time.time() + 2
        with start_at.get_lock():
            start_at.value = epoch
        save(output / 'schedule.json', dict(startAt=epoch, ready=list(prepared.values())))
        gate.set()
        print(f'T2 joined={args.connections}; workers={args.workers}; rates={args.rates}; seconds/phase={args.seconds}', flush=True)
        deadline = time.monotonic() + 2 + Plan(args.rates, args.seconds, args.warmup_seconds).duration + args.drain_seconds + 15
        while any(c.is_alive() for c in children):
            if time.monotonic() >= deadline:
                raise TimeoutError('工作进程超过总运行截止')
            time.sleep(.2)
    except Exception as error:
        fatal = f'{type(error).__name__}:{error}'
    finally:
        cancel.set()
        for child in children:
            child.join(.5)
            if child.is_alive():
                child.terminate()
                child.join(2)
            if child.is_alive():
                child.kill()
                child.join(1)
        ready.close()
        ready.cancel_join_thread()
    processes = [dict(worker=i, pid=child.pid, exitCode=child.exitcode) for i, child in enumerate(children)]
    reports = [json.loads(path.read_text(encoding='utf-8')) for path in sorted(output.glob('worker-*/summary.json'))]
    report = summarize(args, reports, processes, epoch, fatal)
    save(output / 'summary.json', report)
    for phase in report['phases']:
        print(f"T2 {phase['rate']}/s: actual={phase['actualInputPerSecond']:.3f}/s; received={phase['received']}/{phase['acceptedDeliveryDemand']}; rejected={phase['rejected']}; p99={phase['latencyMs']['p99']}ms; toolValid={phase['toolValid']}; candidateSlo={phase['candidateSloPassed']}", flush=True)
    return 0 if report['toolValid'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='ws://127.0.0.1:8085/ws')
    parser.add_argument('--connections', type=int, default=1000)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--rates', default='5,20,50,100')
    parser.add_argument('--seconds', type=int, default=180)
    parser.add_argument('--warmup-seconds', type=int, default=15)
    parser.add_argument('--latency-sample-every', type=int, default=10)
    parser.add_argument('--drain-seconds', type=int, default=7)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        args.rates = [int(value) for value in args.rates.split(',')]
    except ValueError:
        parser.error('rates必须是四个整数')
    target = urlparse(args.url)
    if target.scheme != 'ws' or target.hostname not in ('127.0.0.1', 'localhost'):
        parser.error('只允许本机ws地址')
    if not (len(args.rates) == 4 and all(1 <= rate <= 100 for rate in args.rates)
            and args.rates == sorted(set(args.rates)) and 1 <= args.workers <= 4
            and args.workers * 2 <= args.connections <= 2000 and 1 <= args.seconds <= 180
            and 0 <= args.warmup_seconds <= 60 and 1 <= args.drain_seconds <= 30
            and 1 <= args.latency_sample_every <= 2000):
        parser.error('参数超出有界范围；速率必须是四档递增整数')
    if args.rates[-1] / (args.connections // args.workers) > 4:
        parser.error('第一分片的发送者不足，请增加connections或减少workers；每发送者目标最多4/s')
    if args.connections * Plan(args.rates, args.seconds, args.warmup_seconds).total > 64 * 1024 * 1024:
        parser.error('全部观众去重表合计超过64MiB')
    args.room = 't2-' + os.urandom(8).hex()
    args.slow_connections, args.slow_bytes_per_second = 0, 1024
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    save(output / 'config.json', vars(args))
    raise SystemExit(experiment(args, output))


if __name__ == '__main__':
    main()
