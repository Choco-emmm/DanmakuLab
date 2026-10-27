"""T3/T5 长测：单个 asyncio 事件循环管理真实 TCP 连接，不为每位观众创建线程。

T3 中 slow 观众在预热后按字节限速调用 socket.recv，SO_RCVBUF 也被限制；不是延迟显示。
T5 无慢观众。两种场景均固定完整输入 JSON=256 字节，所有正常观众留在完整率分母内。
完整去重表最多 64MiB；延迟用固定毫秒直方图，不保存数百万个 Python 整数样本。
"""
import argparse
import asyncio
from array import array
import base64
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path
import socket
import struct
import time
from urllib.parse import urlparse


class Histogram:
    """固定容量，精确计量 0..120000ms；更大的值进入末桶，并另外保留溢出数和真实最大值。"""
    def __init__(self):
        self.bins = array('Q', [0]) * 120001
        self.count = self.maximum = self.overflow = self.total = 0

    def add(self, value):
        if value < 0:
            raise ValueError('客户端时间回拨导致负延迟')
        self.bins[min(value, 120000)] += 1
        self.count += 1
        self.total += value
        self.maximum = max(self.maximum, value)
        self.overflow += value > 120000

    def report(self):
        targets = {name: math.ceil(self.count * fraction) for name, fraction in
                   (('p50', .5), ('p95', .95), ('p99', .99))}
        values = {}
        cumulative = 0
        if self.count:
            for value, number in enumerate(self.bins):
                cumulative += number
                for name, target in targets.items():
                    if name not in values and cumulative >= target:
                        values[name] = value
                if len(values) == 3:
                    break
        return dict(samples=self.count, **{name: values.get(name) for name in targets},
                    max=self.maximum if self.count else None,
                    avg=self.total / self.count if self.count else None,
                    overflowAbove120000ms=self.overflow)


def frame(payload, opcode=1):
    """客户端帧按 RFC6455 加随机掩码；先构造完整 bytes，再持发送锁写，避免心跳插入帧中。"""
    mask = os.urandom(4)
    length = len(payload)
    size = bytes([128 | length]) if length < 126 else bytes([128 | 126]) + struct.pack('!H', length)
    return bytes([128 | opcode]) + size + mask + bytes(value ^ mask[i % 4] for i, value in enumerate(payload))


def extract(buffer):
    """从有限字节缓冲取一帧；不完整返回 None，异常帧明确失败，不把字节流误当消息边界。"""
    if len(buffer) < 2:
        return None
    first, second = buffer[0], buffer[1]
    size, offset = second & 127, 2
    if size == 126:
        if len(buffer) < 4:
            return None
        size, offset = struct.unpack('!H', buffer[2:4])[0], 4
    elif size == 127:
        if len(buffer) < 10:
            return None
        size, offset = struct.unpack('!Q', buffer[2:10])[0], 10
    if second & 128 or first & 112 or not first & 128 or size > 65536:
        raise ValueError('不支持掩码、压缩、分片或超过64KiB的服务端帧')
    if len(buffer) < offset + size:
        return None
    payload = bytes(buffer[offset:offset + size])
    del buffer[:offset + size]
    return first & 15, payload


class Peer:
    """一条非阻塞 TCP/WebSocket 连接。慢组只限制 recv，不限制向服务器发送心跳。"""
    def __init__(self, state, index):
        self.state, self.index = state, index
        self.slow = index < state.args.slow_connections
        self.sock = socket.socket()
        self.sock.setblocking(False)
        if self.slow:
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
        self.receive_buffer = self.sock.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)
        self.buffer = bytearray()
        self.write_lock = asyncio.Lock()
        self.alive = True
        self.task = None
        self.next_read = 0.0
        self.measured_bytes = 0

    async def send(self, event):
        payload = json.dumps(event, separators=(',', ':'), ensure_ascii=False).encode()
        async with self.write_lock:
            await asyncio.wait_for(asyncio.get_running_loop().sock_sendall(self.sock, frame(payload)), 3)

    async def receive(self):
        while True:
            event = extract(self.buffer)
            if event is not None:
                opcode, payload = event
                if opcode == 8:
                    raise EOFError('服务端关闭帧:' + payload[:2].hex())
                if opcode == 10:
                    continue
                if opcode == 9:
                    async with self.write_lock:
                        await asyncio.wait_for(asyncio.get_running_loop().sock_sendall(self.sock, frame(payload, 10)), 3)
                    continue
                if opcode != 1:
                    raise ValueError('服务端帧类型不是完整文本')
                return json.loads(payload)
            limited = self.slow and self.state.measuring
            if limited:
                # 一次最多读取约八分之一秒额度；不使用会在后台预读的 asyncio StreamReader。
                await asyncio.sleep(max(0, self.next_read - time.monotonic()))
            block = await asyncio.get_running_loop().sock_recv(self.sock, min(65536, max(1, self.state.args.slow_bytes_per_second // 8)) if limited else 65536)
            if not block:
                raise EOFError('TCP 对端关闭')
            if self.state.measuring:
                self.measured_bytes += len(block)
            if limited:
                self.next_read = time.monotonic() + len(block) / self.state.args.slow_bytes_per_second
            self.buffer.extend(block)
            if len(self.buffer) > 131072:
                raise ValueError('客户端帧缓冲超过128KiB')

    async def open(self):
        async def initialize():
            target = urlparse(self.state.args.url)
            loop = asyncio.get_running_loop()
            await loop.sock_connect(self.sock, (target.hostname, target.port or 80))
            key = base64.b64encode(os.urandom(16)).decode()
            request = (f'GET {target.path or "/"} HTTP/1.1\r\nHost: {target.netloc}\r\nUpgrade: websocket\r\n'
                       f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n')
            await loop.sock_sendall(self.sock, request.encode())
            header = bytearray()
            while b'\r\n\r\n' not in header:
                block = await loop.sock_recv(self.sock, 1024)
                if not block or len(header) + len(block) > 8192:
                    raise ValueError('握手失败或响应头过长')
                header.extend(block)
            raw, remainder = bytes(header).split(b'\r\n\r\n', 1)
            self.buffer.extend(remainder)
            lines = raw.decode().split('\r\n')
            fields = dict((key.lower(), value.strip()) for key, value in (line.split(':', 1) for line in lines[1:]))
            expected = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            if ' 101 ' not in lines[0] or fields.get('sec-websocket-accept') != expected:
                raise ValueError('WebSocket 握手校验失败')
            await self.send(dict(type='join', roomId=self.state.room, nickname=f'viewer-{self.index}'))
            event = await self.receive()
            if event.get('type') != 'joined' or event.get('roomId') != self.state.room:
                raise ValueError('未收到正确 joined')
            instance = event['roomInstanceId']
            if self.state.instance not in (None, instance):
                raise ValueError('房间实例不一致')
            self.state.instance = instance
            self.state.joined += 1
        try:
            # 建连、握手、join 共用总预算，不允许每个字节重新等三秒。
            await asyncio.wait_for(initialize(), 3)
            self.task = asyncio.create_task(self.read_loop())
        except BaseException:
            self.sock.close()
            raise

    async def read_loop(self):
        try:
            while True:
                self.state.event(self, await self.receive())
        except asyncio.CancelledError:
            pass
        except Exception as error:
            self.alive = False
            if not self.state.closing:
                if not isinstance(error, (OSError, EOFError)):
                    self.state.protocol_errors += 1
                self.state.failure(self, 'read', error)

    def close(self):
        self.alive = False
        if self.task:
            self.task.cancel()
        self.sock.close()


class State:
    """所有回调在同一事件循环串行运行；共享统计不依赖多线程/GIL 的偶然原子性。"""
    def __init__(self, args):
        self.args = args
        self.warmup = args.warmup_seconds * args.rate
        self.total = self.warmup + args.seconds * args.rate
        self.room = 'long-' + os.urandom(8).hex()
        self.instance = self.run_id = self.text = None
        self.joined = self.sent = self.duplicates = self.order_errors = self.protocol_errors = 0
        self.measuring = self.closing = False
        self.seen = [bytearray(self.total) for _ in range(args.connections)]
        self.times = array('q', [0]) * self.total
        self.status = bytearray(self.total)
        self.normal_receipts = array('H', [0]) * self.total
        self.last_sequence = array('Q', [0]) * args.connections
        self.sequence_by_input = array('Q', [0]) * self.total
        self.input_by_sequence = array('q', [-1]) * (self.total + 1)
        self.received = [[0, 0] for _ in range(args.connections)] # 每人分别统计预热/测量，断开也不删。
        self.normal_latency, self.slow_latency = Histogram(), Histogram()
        self.failure_counts = Counter()
        self.failure_details = []
        self.rejections = Counter()
        self.max_lag = 0.0
        self.measured_start = self.measured_end = 0.0

    def failure(self, peer, operation, error):
        group = 'slow' if peer.slow else 'normal'
        self.failure_counts[group + ':' + operation] += 1
        if len(self.failure_details) < 200:
            self.failure_details.append(dict(viewer=peer.index, group=group, operation=operation,
                                             error=type(error).__name__ + ':' + str(error), at=time.time()))

    def event(self, peer, event):
        if event.get('type') == 'pong':
            return
        prefix = self.room + ':'
        identity = event.get('clientMessageId', '')
        if not isinstance(identity, str) or not identity.startswith(prefix):
            raise ValueError('未知消息ID或串房')
        index = int(identity[len(prefix):])
        if not 0 <= index < self.total or self.times[index] == 0 or identity != f'{self.room}:{index:06d}':
            raise ValueError('消息编号越界或尚未发送')
        if event.get('type') == 'send_result':
            expected_sender = self.args.slow_connections + index % (self.args.connections - self.args.slow_connections)
            if peer.index != expected_sender or self.status[index]:
                raise ValueError('错误发送者或重复确认')
            if event.get('status') == 'accepted':
                self.status[index] = 1
            elif event.get('status') == 'rejected':
                self.status[index] = 2
                self.rejections[event.get('reason', 'unknown')] += 1
            else:
                raise ValueError('未知发送状态')
            return
        if event.get('type') != 'danmaku' or event.get('roomId') != self.room or event.get('roomInstanceId') != self.instance:
            raise ValueError('未知类型或房间身份错误')
        run = event.get('runId')
        if not run or self.run_id not in (None, run):
            raise ValueError('服务运行标识变化')
        self.run_id = run
        if event.get('clientSentAt') != self.times[index] or event.get('text') != self.text:
            raise ValueError('广播正文或时间戳不一致')
        if self.seen[peer.index][index]:
            self.duplicates += 1
            return
        sequence = event.get('roomSeq')
        if type(sequence) is not int or not 1 <= sequence <= self.total:
            raise ValueError('房间序号非法')
        if (sequence <= self.last_sequence[peer.index]
                or self.sequence_by_input[index] not in (0, sequence)
                or self.input_by_sequence[sequence] not in (-1, index)):
            self.order_errors += 1
        self.last_sequence[peer.index] = self.sequence_by_input[index] = sequence
        self.input_by_sequence[sequence] = index
        self.seen[peer.index][index] = 1
        measured = index >= self.warmup
        self.received[peer.index][int(measured)] += 1
        if not peer.slow:
            self.normal_receipts[index] += 1
        if measured and (peer.slow or (peer.index - self.args.slow_connections) % self.args.latency_sample_every == 0):
            elapsed = time.time_ns() // 1000000 - self.times[index]
            (self.slow_latency if peer.slow else self.normal_latency).add(elapsed)

    def report(self, peers, fatal):
        args = self.args
        normal = args.connections - args.slow_connections
        measured = args.seconds * args.rate
        normal_received = sum(row[1] for row in self.received[args.slow_connections:])
        normal_warmup = sum(row[0] for row in self.received[args.slow_connections:])
        duration = self.measured_end - self.measured_start if self.measured_start and self.measured_end else 0
        measured_sent = max(0, self.sent - self.warmup)
        actual_rate = measured_sent / duration if duration else 0
        missing_accepted = sum(normal - self.normal_receipts[i] for i in range(self.warmup, self.total) if self.status[i] == 1)
        latency = self.normal_latency.report()
        checks = dict(allJoined=self.joined == args.connections, scheduleComplete=self.sent == self.total,
                      acceptedAll=self.status.count(1) == self.total, noMissingResults=sum(1 for i in range(self.sent) if self.status[i] == 0) == 0,
                      normalComplete=normal_received == normal * measured,
                      normalWarmupComplete=normal_warmup == normal * self.warmup,
                      inputRateValid=args.rate * .95 <= actual_rate <= args.rate * 1.05,
                      noNormalFailures=not any(value for key, value in self.failure_counts.items() if key.startswith('normal:')),
                      noDuplicates=self.duplicates == 0, noOrderErrors=self.order_errors == 0,
                      noProtocolErrors=self.protocol_errors == 0, sendLagValid=self.max_lag < 1000,
                      latencySlo=latency['p95'] is not None and latency['p95'] < 200 and latency['p99'] < 500,
                      noFatal=fatal is None)
        return dict(scenario=args.scenario, connections=args.connections, normalConnections=normal,
                    slowConnections=args.slow_connections, slowBytesPerSecond=args.slow_bytes_per_second,
                    warmupSeconds=args.warmup_seconds, measureSeconds=args.seconds, requestBytes=256,
                    joined=self.joined, plannedIncludingWarmup=self.total, submittedIncludingWarmup=self.sent,
                    acceptedIncludingWarmup=self.status.count(1), acceptedMeasured=self.status[self.warmup:].count(1),
                    rejections=dict(self.rejections), normalExpected=normal * measured, normalReceived=normal_received,
                    normalWarmupReceived=normal_warmup, normalExpectedWarmup=normal * self.warmup,
                    normalMissingAccepted=missing_accepted, normalLatencyMs=latency, slowLatencyMs=self.slow_latency.report(),
                    slowReceived=sum(row[1] for row in self.received[:args.slow_connections]),
                    slowPotentialDemandAtInitialMembership=args.slow_connections * measured,
                    slowPerViewer=[dict(viewer=p.index, received=self.received[p.index][1], readBytes=p.measured_bytes,
                                        receiveBufferBytes=p.receive_buffer, alive=p.alive) for p in peers if p.slow],
                    perViewerMeasured=[row[1] for row in self.received], duplicates=self.duplicates,
                    orderViolations=self.order_errors, protocolErrors=self.protocol_errors,
                    failures=dict(self.failure_counts), failureDetails=self.failure_details,
                    actualInputPerSecond=actual_rate, actualMeasureSeconds=duration, maxSendLagMs=self.max_lag,
                    checks=checks, passed=all(checks.values()), fatal=fatal,
                    formalDurationSatisfied=args.seconds >= (900 if args.scenario == 'T3' else 1800),
                    note='Same-host asyncio raw TCP client. Normal denominator never shrinks. Slow starts after warmup; read rate is limited before recv. Slow disconnection is reported separately. Backend evidence still required for actual backpressure. Latency histogram is exact to120s then capped; max/overflow retained.')


async def experiment(args, output):
    state = State(args)
    peers = [Peer(state, index) for index in range(args.connections)]
    tasks = []
    open_tasks = []
    fatal = None
    beginning = time.time()
    try:
        semaphore = asyncio.Semaphore(40)
        async def open_peer(peer):
            async with semaphore:
                await peer.open()
        open_tasks = [asyncio.create_task(open_peer(peer)) for peer in peers]
        await asyncio.wait_for(asyncio.gather(*open_tasks), 60)
        print(f'Joined {state.joined}; scenario={args.scenario}; warmup={args.warmup_seconds}s; measured={args.seconds}s; slow={args.slow_connections}', flush=True)

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
                row = dict(at=time.time(), measuring=state.measuring, sent=state.sent,
                           accepted=state.status.count(1), normalReceived=sum(v[1] for v in state.received[args.slow_connections:]),
                           slowReceived=sum(v[1] for v in state.received[:args.slow_connections]),
                           normalFailures=sum(v for k, v in state.failure_counts.items() if k.startswith('normal:')),
                           maxSendLagMs=state.max_lag)
                with (output / 'progress.jsonl').open('a', encoding='utf-8') as stream:
                    stream.write(json.dumps(row) + '\n')
                print(json.dumps(row), flush=True)
                await asyncio.sleep(5)

        tasks = [asyncio.create_task(heartbeat()), asyncio.create_task(progress())]
        started = time.monotonic()
        for index in range(state.total):
            target = started + index / args.rate
            await asyncio.sleep(max(0, target - time.monotonic()))
            if index == state.warmup:
                state.measuring = True
                state.measured_start = time.monotonic()
                print('Measurement started; slow recv throttling enabled where configured', flush=True)
            now = time.time_ns() // 1000000
            message = dict(type='send', clientMessageId=f'{state.room}:{index:06d}', text='', clientSentAt=now)
            padding = 256 - len(json.dumps(message, separators=(',', ':')).encode())
            if not 1 <= padding <= 200:
                raise ValueError('无法构造256字节合法输入')
            state.text = message['text'] = 'x' * padding
            state.times[index] = now
            if index >= state.warmup:
                state.max_lag = max(state.max_lag, (time.monotonic() - target) * 1000)
            sender = peers[args.slow_connections + index % (args.connections - args.slow_connections)]
            try:
                await sender.send(message)
            except Exception as error:
                state.failure(sender, 'send', error)
                raise
            state.sent += 1
        await asyncio.sleep(max(0, started + args.warmup_seconds + args.seconds - time.monotonic()))
        state.measured_end = time.monotonic()
        await asyncio.sleep(args.drain_seconds)
    except Exception as error:
        fatal = type(error).__name__ + ':' + str(error)
    finally:
        # 先截取统计，再发起本地关闭；工具自己的清理不能冒充服务端异常断连。
        report = state.report(peers, fatal)
        (output / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        state.closing = True
        for task in open_tasks:
            if not task.done():
                task.cancel()
        for task in tasks:
            task.cancel()
        for peer in peers:
            peer.close()
        await asyncio.gather(*open_tasks, *tasks, *(p.task for p in peers if p.task), return_exceptions=True)
        (output / 'timing.json').write_text(json.dumps(dict(startedAt=beginning, endedAt=time.time())), encoding='utf-8')
    print(json.dumps({key: report[key] for key in ('scenario', 'passed', 'normalExpected', 'normalReceived', 'normalLatencyMs', 'failures', 'checks')}), flush=True)
    return 0 if report['passed'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=['T3', 'T5'], required=True)
    parser.add_argument('--url', default='ws://127.0.0.1:8083/ws')
    parser.add_argument('--connections', type=int, default=1000)
    parser.add_argument('--seconds', type=int, required=True)
    parser.add_argument('--warmup-seconds', type=int, default=60)
    parser.add_argument('--rate', type=int, default=20)
    parser.add_argument('--slow-connections', type=int, default=0)
    parser.add_argument('--slow-bytes-per-second', type=int, default=1024)
    parser.add_argument('--latency-sample-every', type=int, default=10)
    parser.add_argument('--drain-seconds', type=int, default=7)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if urlparse(args.url).hostname not in ('127.0.0.1', 'localhost') or urlparse(args.url).scheme != 'ws':
        parser.error('只支持本机 ws 地址')
    if not (1 <= args.connections <= 2000 and 1 <= args.seconds <= 3600 and 0 <= args.warmup_seconds <= 300
            and 1 <= args.rate <= 40 and 0 <= args.slow_connections < args.connections
            and 1 <= args.slow_bytes_per_second <= 1048576 and 1 <= args.latency_sample_every <= 2000
            and 1 <= args.drain_seconds <= 30):
        parser.error('参数超出有界实验范围')
    if (args.scenario == 'T5' and args.slow_connections) or (args.scenario == 'T3' and not args.slow_connections):
        parser.error('T3必须包含慢观众，T5必须全部正常读取')
    if args.connections * (args.warmup_seconds + args.seconds) * args.rate > 64 * 1024 * 1024:
        parser.error('去重表超过64MiB，请降低连接、时长或速率')
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    (output / 'config.json').write_text(json.dumps(vars(args), indent=2), encoding='utf-8')
    raise SystemExit(asyncio.run(experiment(args, output)))


if __name__ == '__main__':
    main()
