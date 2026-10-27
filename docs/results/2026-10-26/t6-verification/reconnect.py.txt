"""T6批量重连：一组固定在线，另一组关闭TCP后按指数退避与全抖动重新join。

复用既有原始WebSocket连接，不改浏览器或Java。断线期不承诺历史补发；固定在线组
始终保留完整分母，恢复窗口要求初始全部观众收齐。额外探针在服务满额时验证1013。
"""
import argparse
import asyncio
from array import array
from collections import Counter
import json
import math
import os
from pathlib import Path
import random
import re
import time
from urllib.parse import urlparse

from longrun import Histogram, Peer


def save(path, data):
    """新建结果目录内原子替换自己的小文件，便于父采集器读取进度和检查点。"""
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def backoff(attempt, rng):
    """与app.js相同：第0/1/2次上限1/2/4秒，最高30秒，再乘[0,1)随机数。"""
    if type(attempt) is not int or attempt < 0:
        raise ValueError('attempt必须是非负整数')
    cap = min(30000, 1000 * 2 ** min(attempt, 5))
    return cap, rng.random() * cap


def close_code(error):
    """既有Peer只在收到真实关闭帧时生成该文本；TCP EOF/超时不能冒充1013准入拒绝。"""
    matched = re.fullmatch(r'服务端关闭帧:([0-9a-f]{4})', str(error)) if isinstance(error, EOFError) else None
    return int(matched.group(1), 16) if matched else None


class Plan:
    """窗口提前编号；晚到广播按输入ID归属原窗口，不用当下在线人数改写历史分母。"""
    def __init__(self, args):
        self.phases = []
        self.total = 0
        definitions = [('baseline', args.steady_seconds, False)]
        for cycle in range(1, args.cycles + 1):
            definitions += [(f'churn-{cycle}', args.churn_seconds, True),
                            (f'recovered-{cycle}', args.steady_seconds, False)]
        for name, seconds, churn in definitions:
            count = args.rate * seconds
            self.phases.append(dict(name=name, seconds=seconds, churn=churn, offset=self.total, count=count))
            self.total += count
        self.by_input = bytearray(self.total)
        for p, phase in enumerate(self.phases):
            self.by_input[phase['offset']:phase['offset'] + phase['count']] = bytes([p]) * phase['count']


class ReconnectPeer(Peer):
    """同一逻辑观众每次重连创建新Peer；generation区分旧socket，不把旧连接恢复成新连接。"""
    def __init__(self, state, index, generation=0, probe=False):
        super().__init__(state, index)
        self.generation, self.probe = generation, probe
        self.intentional = False
        self.connection_id = None
        self.server_close_code = None

    async def receive(self):
        try:
            event = await super().receive()
        except EOFError as error:
            self.server_close_code = close_code(error)
            raise
        if event.get('type') == 'joined':
            identity = event.get('connectionId')
            if not isinstance(identity, str) or not identity or identity in self.state.connection_ids:
                raise ValueError('joined缺少新连接ID，或重复复用旧ID')
            self.connection_id = identity
            self.state.connection_ids.add(identity)
            self.state.joins.append(dict(viewer=self.index, generation=self.generation,
                probe=self.probe, connectionId=identity, at=time.time()))
        return event


class State:
    """全部收发在一个asyncio事件循环；统计有界，不按每条消息启动线程或创建无界历史列表。"""
    def __init__(self, args):
        self.args, self.plan = args, Plan(args)
        self.room = 't6-' + os.urandom(8).hex()
        self.instance = self.run_id = None
        self.joined = self.duplicates = self.order_errors = self.protocol_errors = 0
        self.closing = self.measuring = False
        self.stable = args.connections - args.affected
        self.peers = [None] * args.connections
        self.created = []
        self.connection_ids, self.joins = set(), []
        self.seen = [bytearray(self.plan.total) for _ in range(args.connections)]
        self.times = array('q', [0]) * self.plan.total
        self.status = bytearray(self.plan.total)
        self.required_receipts = array('H', [0]) * self.plan.total
        self.all_receipts = array('H', [0]) * self.plan.total
        self.last_sequence = array('Q', [0]) * args.connections
        self.sequence_by_input = array('Q', [0]) * self.plan.total
        self.input_by_sequence = array('i', [-1]) * (self.plan.total + 1)
        self.per_viewer = [[0] * len(self.plan.phases) for _ in range(args.connections)]
        self.latency = [Histogram() for _ in self.plan.phases]
        self.sent = [0] * len(self.plan.phases)
        self.duration = [0.0] * len(self.plan.phases)
        self.max_lag = [0.0] * len(self.plan.phases)
        self.reasons = [Counter() for _ in self.plan.phases]
        self.failures, self.failure_details = Counter(), []
        self.retries, self.recoveries, self.probes = [], [], []
        self.planned_disconnects = 0
        self.phase_windows = []
        self.text = None

    def failure(self, peer, operation, error):
        """主动取消旧socket不是意外断连；新连接与固定在线观众的读失败必须保留并导致失败。"""
        if peer.intentional:
            return
        self.failures[operation] += 1
        if len(self.failure_details) < 100:
            self.failure_details.append(dict(viewer=peer.index, generation=peer.generation,
                operation=operation, error=f'{type(error).__name__}:{error}', at=time.time()))

    def event(self, peer, event):
        """按逻辑观众跨代去重、按房间实例校验；重连允许序号缺口，但不允许倒序或重复补发。"""
        if event.get('type') == 'pong':
            return
        if peer.probe or not 0 <= peer.index < self.args.connections:
            raise ValueError('超额探针不应成为房间观众')
        identity = event.get('clientMessageId', '')
        prefix = self.room + ':'
        if not isinstance(identity, str) or not identity.startswith(prefix):
            raise ValueError('未知消息ID或串房')
        index = int(identity[len(prefix):])
        if (not 0 <= index < self.plan.total or identity != f'{self.room}:{index:06d}' or not self.times[index]):
            raise ValueError('输入ID越界或尚未发送')
        p = self.plan.by_input[index]
        if event.get('type') == 'send_result':
            if peer.index != index % self.stable or self.status[index]:
                raise ValueError('错误发送者或重复确认')
            if event.get('status') == 'accepted':
                self.status[index] = 1
            elif event.get('status') == 'rejected':
                self.status[index] = 2
                self.reasons[p][event.get('reason', 'unknown')] += 1
            else:
                raise ValueError('未知发送结果')
            return
        if (event.get('type') != 'danmaku' or event.get('roomId') != self.room
                or event.get('roomInstanceId') != self.instance or event.get('text') != self.text
                or event.get('clientSentAt') != self.times[index]):
            raise ValueError('广播类型、房间、正文或时间戳错误')
        run_id = event.get('runId')
        if not run_id or self.run_id not in (None, run_id):
            raise ValueError('服务运行标识变化')
        self.run_id = run_id
        if self.seen[peer.index][index]:
            self.duplicates += 1
            return
        sequence = event.get('roomSeq')
        if type(sequence) is not int or not 1 <= sequence <= self.plan.total:
            raise ValueError('非法房间序号')
        if (sequence <= self.last_sequence[peer.index] or self.sequence_by_input[index] not in (0, sequence)
                or self.input_by_sequence[sequence] not in (-1, index)):
            self.order_errors += 1
        self.last_sequence[peer.index] = self.sequence_by_input[index] = sequence
        self.input_by_sequence[sequence] = index
        self.seen[peer.index][index] = 1
        self.all_receipts[index] += 1
        self.per_viewer[peer.index][p] += 1
        required = peer.index < self.stable or not self.plan.phases[p]['churn']
        if required:
            self.required_receipts[index] += 1
            if peer.index % self.args.latency_sample_every == 0:
                self.latency[p].add(time.time_ns() // 1000000 - self.times[index])

    async def connect(self, index, generation=0, probe=False):
        peer = ReconnectPeer(self, index, generation, probe)
        self.created.append(peer)
        if not probe:
            self.peers[index] = peer
        try:
            await peer.open()
            return peer
        except BaseException:
            peer.intentional = True
            peer.close()
            raise

    async def send_window(self, p):
        """固定速率只由始终在线组轮转发送；窗口停发后仍有限排空，不删除缺失消息。"""
        phase = self.plan.phases[p]
        begin, began_epoch = time.monotonic(), time.time()
        print(f"T6 window {phase['name']}; required viewers={self.stable if phase['churn'] else self.args.connections}", flush=True)
        for number in range(phase['count']):
            index = phase['offset'] + number
            target = begin + number / self.args.rate
            await asyncio.sleep(max(0, target - time.monotonic()))
            self.max_lag[p] = max(self.max_lag[p], (time.monotonic() - target) * 1000)
            event = dict(type='send', clientMessageId=f'{self.room}:{index:06d}', text='', clientSentAt=time.time_ns() // 1000000)
            padding = 256 - len(json.dumps(event, separators=(',', ':')).encode())
            if not 1 <= padding <= 200:
                raise ValueError('无法构造合法256字节输入')
            event['text'] = self.text = 'x' * padding
            self.times[index] = event['clientSentAt']
            await self.peers[index % self.stable].send(event)
            self.sent[p] += 1
        await asyncio.sleep(max(0, begin + phase['seconds'] - time.monotonic()))
        self.duration[p] = time.monotonic() - begin
        self.phase_windows.append(dict(name=phase['name'], beganAt=began_epoch, endedAt=time.time()))

    async def retry(self, index, generation, cycle, probe=False):
        """每个逻辑观众独立随机源；真实失败后增加退避次数，达到次数或总截止即停止，不无限重试。"""
        rng = random.Random(self.args.seed + cycle * 10000 + index)
        limit = self.args.probe_attempts if probe else self.args.max_attempts
        deadline = time.monotonic() + self.args.recovery_deadline
        records = []
        for attempt in range(limit):
            cap, delay = backoff(attempt, rng)
            if time.monotonic() + delay / 1000 >= deadline:
                records.append(dict(attempt=attempt, capMs=cap, delayMs=delay, outcome='deadline_before_attempt'))
                break
            waited = time.monotonic()
            await asyncio.sleep(delay / 1000)
            record = dict(viewer=index, generation=generation, cycle=cycle, probe=probe, attempt=attempt,
                capMs=cap, delayMs=delay, waitedMs=(time.monotonic() - waited) * 1000, startedAt=time.time())
            peer = None
            try:
                # connect失败时保留对应Peer关闭码，不能把普通网络错误算成1013拒绝。
                peer = ReconnectPeer(self, index, generation, probe)
                self.created.append(peer)
                if not probe:
                    self.peers[index] = peer
                await asyncio.wait_for(peer.open(), max(.01, min(3, deadline - time.monotonic())))
                record.update(outcome='joined', connectionId=peer.connection_id, endedAt=time.time())
                records.append(record)
                if probe:
                    peer.intentional = True
                    peer.close()
                self.retries.extend(records)
                return not probe
            except Exception as error:
                record.update(outcome='rejected_1013' if peer and peer.server_close_code == 1013 else 'other_failure',
                    closeCode=peer.server_close_code if peer else None, error=f'{type(error).__name__}:{error}', endedAt=time.time())
                records.append(record)
                if peer:
                    peer.intentional = True
                    peer.close()
        self.retries.extend(records)
        return probe and len(records) == limit and all(record['outcome'] == 'rejected_1013' for record in records)

    def report(self, fatal):
        """稳定窗口要求全员；重连窗口只保证固定在线组，并保留全体最初人数对应的潜在需求。"""
        phases = []
        for p, phase in enumerate(self.plan.phases):
            lo, hi = phase['offset'], phase['offset'] + phase['count']
            cohort = self.stable if phase['churn'] else self.args.connections
            accepted = self.status[lo:hi].count(1)
            required_received = sum(self.required_receipts[lo:hi])
            counts = [self.per_viewer[v][p] for v in range(cohort)]
            rate = self.sent[p] / self.duration[p] if self.duration[p] else 0
            latency = self.latency[p].report()
            phases.append(dict(name=phase['name'], churn=phase['churn'], planned=phase['count'], sent=self.sent[p],
                accepted=accepted, rejected=self.status[lo:hi].count(2), rejectionReasons=dict(self.reasons[p]),
                requiredViewers=cohort, nominalRequiredDemand=phase['count'] * cohort,
                acceptedRequiredDemand=accepted * cohort, requiredReceived=required_received,
                missingAccepted=accepted * cohort - required_received, allInitialViewerPotentialDemand=phase['count'] * self.args.connections,
                allReceived=sum(self.all_receipts[lo:hi]), perViewerReceived=[row[p] for row in self.per_viewer],
                actualInputPerSecond=rate, actualSeconds=self.duration[p], maxSendLagMs=self.max_lag[p], latencyMs=latency,
                passed=self.sent[p] == accepted == phase['count'] and required_received == accepted * cohort
                    and all(value == accepted for value in counts) and self.args.rate * .95 <= rate <= self.args.rate * 1.05
                    and self.max_lag[p] < 1000 and latency['p95'] is not None and latency['p95'] < 200
                    and latency['p99'] < 500 and latency['overflowAbove120000ms'] == 0))
        probe_ok = (len(self.probes) == self.args.probes and all(self.probes))
        recovery_ok = len(self.recoveries) == self.args.cycles and all(r['allRejoined'] for r in self.recoveries)
        checks = dict(allInitialJoined=sum(j['generation'] == 0 and not j['probe'] for j in self.joins) == self.args.connections,
            allWindowsPassed=all(p['passed'] for p in phases), admissionProbesRejected=probe_ok,
            recoveredAll=recovery_ok, plannedDisconnects=self.planned_disconnects == self.args.affected * self.args.cycles,
            noUnexpectedFailures=not self.failures, noProtocolErrors=self.protocol_errors == 0,
            noDuplicates=self.duplicates == 0, noOrderErrors=self.order_errors == 0,
            noMissingConfirmations=0 not in self.status, noFatal=fatal is None)
        return dict(scenario='T6', connections=self.args.connections, stableConnections=self.stable,
            affectedConnections=self.args.affected, cycles=self.args.cycles, initialMembershipNeverShrinks=True,
            plannedInputs=self.plan.total, sent=sum(self.sent), accepted=self.status.count(1), rejected=self.status.count(2),
            requiredReceived=sum(self.required_receipts), allReceived=sum(self.all_receipts),
            joins=self.joins, uniqueConnectionIds=len(self.connection_ids), plannedDisconnects=self.planned_disconnects,
            retries=self.retries, recovery=self.recoveries, probeResults=self.probes,
            observedAdmissionRejections=sum(r['outcome'] == 'rejected_1013' for r in self.retries),
            phases=phases, phaseWindows=self.phase_windows, failures=dict(self.failures), failureDetails=self.failure_details,
            duplicates=self.duplicates, orderViolations=self.order_errors, protocolErrors=self.protocol_errors,
            checks=checks, passed=all(checks.values()), fatal=fatal,
            note='Python模拟客户端遵循网页退避规则，不是浏览器自动化。主动关闭TCP而未发送WebSocket关闭帧；不模拟断网后长时间无FIN。重连期固定在线组全收，离线组不要求历史补发；潜在需求不删除。')


async def experiment(args, output):
    state, tasks, opening, fatal = State(args), [], [], None
    try:
        semaphore = asyncio.Semaphore(20)
        async def initial(index):
            async with semaphore:
                await state.connect(index)
        opening = [asyncio.create_task(initial(index)) for index in range(args.connections)]
        await asyncio.wait_for(asyncio.gather(*opening), 60)
        print(f'T6 initial joined={state.joined}; stable={state.stable}; affected={args.affected}', flush=True)

        async def heartbeat():
            while True:
                await asyncio.sleep(15)
                for peer in list(state.peers):
                    if peer and peer.alive and not peer.intentional:
                        try:
                            await peer.send(dict(type='ping'))
                        except Exception as error:
                            state.failure(peer, 'heartbeat', error)

        async def progress():
            while True:
                with (output / 'progress.jsonl').open('a', encoding='utf-8') as stream:
                    stream.write(json.dumps(dict(at=time.time(), sent=sum(state.sent), accepted=state.status.count(1),
                        requiredReceived=sum(state.required_receipts), allReceived=sum(state.all_receipts),
                        liveClients=sum(bool(peer and peer.alive and not peer.intentional) for peer in state.peers),
                        retries=len(state.retries), failures=dict(state.failures))) + '\n')
                await asyncio.sleep(1)

        tasks = [asyncio.create_task(heartbeat()), asyncio.create_task(progress())]
        # 满额时额外探针真实重试三次；可覆盖1/2/4秒上限的抖动，而不只测试重连一次就成功。
        async def probe(index):
            result = await state.retry(args.connections + index, -1, 0, True)
            state.probes.append(result)
        probe_tasks = [asyncio.create_task(probe(index)) for index in range(args.probes)]
        tasks += probe_tasks
        await state.send_window(0)
        await asyncio.wait_for(asyncio.gather(*probe_tasks), args.recovery_deadline + 3)
        await asyncio.sleep(args.drain_seconds)
        if not all(state.probes):
            raise RuntimeError('满额探针没有全部观察到真实1013拒绝')
        for cycle in range(1, args.cycles + 1):
            dropped_at = time.time()
            # 先标记计划内关闭，再取消读取并关闭TCP；所有受影响客户端在同一事件循环轮次内操作。
            for peer in state.peers[state.stable:]:
                peer.intentional = True
                peer.close()
                state.planned_disconnects += 1
            recoveries = [asyncio.create_task(state.retry(index, cycle, cycle)) for index in range(state.stable, args.connections)]
            tasks += recoveries
            await state.send_window(cycle * 2 - 1)
            results = await asyncio.wait_for(asyncio.gather(*recoveries), args.recovery_deadline + 3)
            success_records = [r for r in state.retries if not r.get('probe') and r.get('cycle') == cycle and r['outcome'] == 'joined']
            state.recoveries.append(dict(cycle=cycle, droppedAt=dropped_at,
                allRejoined=all(results), recovered=len(success_records),
                recoveryMs=(max(r['endedAt'] for r in success_records) - dropped_at) * 1000 if success_records else None))
            if not all(results):
                raise RuntimeError('恢复截止前未能全部重新join')
            await asyncio.sleep(args.drain_seconds)
            await state.send_window(cycle * 2)
            await asyncio.sleep(args.drain_seconds)
            save(output / f'checkpoint-{cycle}.json', dict(at=time.time(), cycle=cycle,
                liveClients=sum(bool(peer and peer.alive and not peer.intentional) for peer in state.peers),
                successfulJoins=len([j for j in state.joins if not j['probe']])))
    except Exception as error:
        fatal = f'{type(error).__name__}:{error}'
    finally:
        report = state.report(fatal)
        # 单独保存逐输入原始计数和非零毫秒桶；独立复核器不调用report方法来验证自身。
        save(output / 'observations.json', dict(status=list(state.status), timestamps=list(state.times),
            requiredReceipts=list(state.required_receipts), allReceipts=list(state.all_receipts),
            perViewer=state.per_viewer, sequenceByInput=list(state.sequence_by_input),
            histograms=[[[value, count] for value, count in enumerate(h.bins) if count] for h in state.latency]))
        save(output / 'summary.json', report)
        state.closing = True
        for task in opening + tasks:
            task.cancel()
        for peer in state.created:
            peer.intentional = True
            peer.close()
        await asyncio.gather(*opening, *tasks, *(p.task for p in state.created if p.task), return_exceptions=True)
    print(json.dumps(dict(passed=report['passed'], requiredReceived=report['requiredReceived'],
        recovery=report['recovery'], admissionRejections=report['observedAdmissionRejections'], checks=report['checks'])), flush=True)
    return 0 if report['passed'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='ws://127.0.0.1:8086/ws')
    parser.add_argument('--connections', type=int, default=100)
    parser.add_argument('--affected', type=int)
    parser.add_argument('--cycles', type=int, default=3)
    parser.add_argument('--steady-seconds', type=int, default=10)
    parser.add_argument('--churn-seconds', type=int, default=10)
    parser.add_argument('--rate', type=int, default=20)
    parser.add_argument('--recovery-deadline', type=int, default=15)
    parser.add_argument('--max-attempts', type=int, default=6)
    parser.add_argument('--probes', type=int, default=3)
    parser.add_argument('--probe-attempts', type=int, default=3)
    parser.add_argument('--seed', type=int, default=20261026)
    parser.add_argument('--latency-sample-every', type=int, default=10)
    parser.add_argument('--drain-seconds', type=int, default=2)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    args.affected = args.connections // 2 if args.affected is None else args.affected
    target = urlparse(args.url)
    if target.scheme != 'ws' or target.hostname not in ('127.0.0.1', 'localhost'):
        parser.error('只允许本机ws地址')
    if not (4 <= args.connections <= 500 and 1 <= args.affected < args.connections and 1 <= args.cycles <= 5
            and 1 <= args.steady_seconds <= 60 and 1 <= args.churn_seconds <= 60 and 1 <= args.rate <= 40
            and 5 <= args.recovery_deadline <= 60 and 1 <= args.max_attempts <= 6 and 1 <= args.probes <= 10
            and 1 <= args.probe_attempts <= 3 and 1 <= args.latency_sample_every <= args.connections
            and 1 <= args.drain_seconds <= 5):
        parser.error('参数超出有界实验范围')
    if args.rate / (args.connections - args.affected) > 4:
        parser.error('固定在线发送者不足，每人目标最多4/s')
    if args.connections * Plan(args).total > 64 * 1024 * 1024:
        parser.error('去重表超过64MiB')
    args.slow_connections, args.slow_bytes_per_second = 0, 1024
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    save(output / 'config.json', vars(args))
    raise SystemExit(asyncio.run(experiment(args, output)))


if __name__ == '__main__':
    main()
