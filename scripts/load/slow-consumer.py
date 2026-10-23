"""真实 TCP 慢消费者实验，Python 标准库即可运行，无第三方包。

这不是通用 WebSocket 库：仅实现本地 ws 文本协议所需的握手、掩码发送和帧读取。
探针先加入房间，再停止 socket.recv；SO_RCVBUF 限制接收缓冲，使真实 TCP 背压
较早出现。正常组与慢组使用同一代码、相同消息和速率，只改变探针是否继续读取。
"""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import socket
import struct
import threading
import time
from urllib.parse import urlparse


class Client:
    """一条真实 TCP/WebSocket 连接；读线程和发送线程共享 socket，写入用锁串行化。"""

    def __init__(self, url, nickname, room, callback, failures, receive_buffer=None):
        target = urlparse(url)
        if target.scheme != 'ws' or target.hostname not in ('127.0.0.1', 'localhost'):
            raise ValueError('本实验只支持本机明文 ws://127.0.0.1 或 ws://localhost')
        self.name = nickname
        self.callback = callback
        self.failures = failures
        self.ready = threading.Event()
        self.stop = threading.Event()
        self.write_lock = threading.Lock()
        self.sock = socket.socket()
        # 三秒是建连、握手及 joined 确认共享的总预算，不是每次 recv 各等三秒。
        # 使用单调时钟，防止对端慢慢发几个字节就无限延长初始化。
        self.initialization_deadline = time.monotonic() + 3
        self.thread = None
        try:
            if receive_buffer:
                self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, receive_buffer)
            self.receive_buffer = self.sock.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)
            self._limit_initialization_wait()
            self.sock.connect((target.hostname, target.port or 80))
            key = base64.b64encode(os.urandom(16)).decode()
            request = (f'GET {target.path or "/"} HTTP/1.1\r\nHost: {target.netloc}\r\n'
                       f'Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n'
                       'Sec-WebSocket-Version: 13\r\n\r\n')
            self._limit_initialization_wait()
            self.sock.sendall(request.encode())
            # 逐字节读取握手头，避免误吞握手后的首个 WebSocket 帧。
            header = bytearray()
            while not header.endswith(b'\r\n\r\n'):
                if len(header) >= 8192:
                    raise ValueError('握手响应头过长')
                header.extend(self.exact(1))
            expected = base64.b64encode(hashlib.sha1(
                (key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            headers = header.decode().split('\r\n')
            fields = dict(line.split(':', 1) for line in headers[1:] if ':' in line)
            if ' 101 ' not in headers[0] or next((v.strip() for k, v in fields.items()
                    if k.lower() == 'sec-websocket-accept'), '') != expected:
                raise ValueError('WebSocket 握手校验失败')
            self.send({'type': 'join', 'roomId': room, 'nickname': nickname})
            event = self.read_event()
            if event.get('type') != 'joined' or event.get('roomId') != room:
                raise ValueError(f'加入失败：{event}')
            self.ready.set()
            self.initialization_deadline = None
            self.sock.settimeout(3)
        except Exception:
            # 构造失败时对象尚未加入 clients，外层 finally 无法替它回收 socket。
            self.sock.close()
            raise
        # 已收到 joined 后才决定是否启动读取线程；不读模式不会消费后续 TCP 数据。

    def _limit_initialization_wait(self):
        """每次初始化网络操作只使用剩余秒数；成功加入后的普通读取不受总预算限制。"""
        if self.initialization_deadline is not None:
            remaining = self.initialization_deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('连接、WebSocket 握手或加入房间超过三秒总预算')
            self.sock.settimeout(min(3, remaining))

    def exact(self, length):
        """TCP 是字节流，一次 recv 不保证取齐指定字节，所以循环读取直到完整或断开。"""
        result = bytearray()
        while len(result) < length:
            self._limit_initialization_wait()
            try:
                block = self.sock.recv(length - len(result))
            except socket.timeout as error:
                if not self.ready.is_set():
                    raise TimeoutError('等待 WebSocket 握手或 joined 确认超时') from error
                if self.stop.is_set():
                    raise EOFError()
                continue
            if not block:
                raise EOFError('对端断开')
            result.extend(block)
        return bytes(result)

    def frame(self, opcode, payload):
        # RFC 6455：客户端发往服务器的帧必须掩码；掩码不是加密。
        mask = os.urandom(4)
        length = len(payload)
        size = bytes([length | 128]) if length < 126 else bytes([126 | 128]) + struct.pack('!H', length)
        body = bytes(value ^ mask[i % 4] for i, value in enumerate(payload))
        with self.write_lock:
            self._limit_initialization_wait()
            self.sock.sendall(bytes([128 | opcode]) + size + mask + body)

    def send(self, value):
        self.frame(1, json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode())

    def read_event(self):
        # 本服务输出完整文本帧；遇到分片或非文本数据直接报错，不悄悄误算接收。
        while True:
            first, second = self.exact(2)
            length = second & 127
            if length == 126:
                length = struct.unpack('!H', self.exact(2))[0]
            elif length == 127:
                length = struct.unpack('!Q', self.exact(8))[0]
            if second & 128 or length > 65536:
                raise ValueError('不支持掩码服务端帧或超过 64KiB 的帧')
            payload = self.exact(length)
            opcode = first & 15
            if opcode == 8:
                raise EOFError(f'关闭帧：{payload[:2].hex()}')
            if opcode == 9:
                self.frame(10, payload)
                continue
            if opcode == 10:
                continue
            if opcode != 1 or not first & 128:
                raise ValueError('本实验未实现分片或二进制帧')
            return json.loads(payload)

    def start_reading(self):
        def read_loop():
            try:
                while not self.stop.is_set():
                    self.callback(self.name, self.read_event())
            except Exception as error:
                if not self.stop.is_set():
                    self.failures.append({'client': self.name, 'error': str(error), 'at': time.time()})
        self.thread = threading.Thread(target=read_loop, daemon=True)
        self.thread.start()

    def close(self):
        self.stop.set()
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()
        if self.thread:
            self.thread.join(timeout=4)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['normal', 'paused'], required=True)
    parser.add_argument('--url', default='ws://127.0.0.1:8081/ws')
    parser.add_argument('--seconds', type=int, default=180)
    parser.add_argument('--rate', type=int, default=40)
    parser.add_argument('--viewers', type=int, default=10)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 900 or not 1 <= args.rate <= 40 or not 10 <= args.viewers <= 100:
        parser.error('seconds 必须 1..900，rate 必须 1..40，viewers 必须 10..100')
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    count = args.seconds * args.rate
    # 每人只保存本轮有限消息编号；读回调的共享计数用锁保护，不依赖 GIL 的偶然行为。
    state_lock = threading.Lock()
    failures, clients, rejections = [], [], []
    failed_writers = set()
    seen = {f'normal-{i}': set() for i in range(args.viewers)}
    seen['probe'] = set()
    latencies, accepted, duplicate = [], set(), [0]
    previous = {name: 0 for name in seen}
    order_errors = [0]
    start = time.time()
    prefix = f'slow-{time.time_ns()}'
    room = prefix
    sent_times = {}
    # attempted 是调用过发送的条数；submitted 只统计 sendall 正常返回的条数。
    # submitted 仍不等于后端 accepted，更不等于观众实际收到。
    submitted = 0

    def on_event(name, event):
        with state_lock:
            if event['type'] == 'danmaku':
                index = int(event['clientMessageId'].split(':')[-1])
                if event['roomId'] != room or index not in sent_times:
                    raise ValueError('收到未知消息或错误房间')
                if index in seen[name]:
                    duplicate[0] += 1
                    return
                if event['roomSeq'] <= previous[name]:
                    order_errors[0] += 1
                previous[name] = event['roomSeq']
                seen[name].add(index)
                if name != 'probe':
                    latencies.append((time.time_ns() // 1000000) - sent_times[index])
            elif event['type'] == 'send_result':
                if event['status'] == 'accepted':
                    accepted.add(event['clientMessageId'])
                else:
                    rejections.append(event)
            elif event['type'] != 'pong':
                failures.append({'client': name, 'event': event})

    try:
        # 探针最先入房，使它位于直接广播快照前部；两组顺序一致。
        probe = Client(args.url, 'probe', room, on_event, failures, receive_buffer=1024)
        clients.append(probe)
        if args.mode == 'normal':
            probe.start_reading()
        for i in range(args.viewers):
            client = Client(args.url, f'normal-{i}', room, on_event, failures)
            clients.append(client)
            client.start_reading()
        started = time.monotonic()
        print(f'Joined {len(clients)}; mode={args.mode}; probe SO_RCVBUF={probe.receive_buffer}; input={args.rate}/s', flush=True)
        samples = []
        for index in range(count):
            target = started + index / args.rate
            time.sleep(max(0, target - time.monotonic()))
            timestamp = time.time_ns() // 1000000
            # 200 个 BMP 汉字=600 UTF-8 字节，仍在正文和完整输入帧上限内。
            # 较 T1 增大负载，两组模板一致，不能直接把两组结果与 T1 混作同负载比较。
            message = {'type': 'send', 'clientMessageId': f'{prefix}:{index}',
                       'text': '弹' * 200, 'clientSentAt': timestamp}
            with state_lock:
                # 必须在发送前登记：接收线程可能在 send 返回前就处理服务器回复。
                # 即使发送失败也保留这次尝试；是否成功提交由 submitted 单独计量。
                sent_times[index] = timestamp
            sender = clients[1 + index % args.viewers]
            try:
                sender.send(message)
            except OSError as error:
                # 正常发送者失败不能从观众分母删除；记录并停止输入，保留未完成状态。
                failures.append({'client': sender.name, 'operation': 'send', 'error': str(error)})
                break
            submitted += 1
            if index % (args.rate * 5) == 0:
                with state_lock:
                    sample = {'seconds': time.monotonic() - started, 'sent': submitted,
                              'accepted': len(accepted), 'normalReceived': sum(len(seen[c.name]) for c in clients[1:]),
                              'probeReceived': len(seen['probe'])}
                samples.append(sample)
                print(json.dumps(sample), flush=True)
            # 即使探针不读取，仍主动发应用心跳，避免把单纯空闲超时误当成发送背压。
            if index % (args.rate * 10) == 0:
                for client in clients:
                    if client.name in failed_writers:
                        continue
                    try:
                        client.send({'type': 'ping'})
                    except OSError as error:
                        # 慢探针可能已被服务端断开。心跳写失败应归档，不能让工具崩溃
                        # 而丢掉正常观众统计；后续不再向这个已失败的连接发送心跳。
                        failed_writers.add(client.name)
                        failures.append({'client': client.name, 'operation': 'heartbeat',
                                         'error': str(error), 'seconds': time.monotonic() - started})
        elapsed = time.monotonic() - started
        time.sleep(7)
        with state_lock:
            ordered = sorted(latencies)
            def percentile(p):
                return ordered[max(0, math.ceil(len(ordered) * p) - 1)] if ordered else None
            report = {'mode': args.mode, 'plannedInputMessages': count, 'inputMessages': submitted,
                      'attemptedInputMessages': len(sent_times), 'submittedInputMessages': submitted,
                      'normalViewers': args.viewers,
                      'accepted': len(accepted), 'normalExpected': count * args.viewers,
                      'normalReceived': sum(len(seen[c.name]) for c in clients[1:]),
                      'perViewerReceived': {name: len(ids) for name, ids in seen.items()},
                      'probeReceiveBufferBytes': probe.receive_buffer,
                      'normalLatencyMs': {'samples': len(ordered), 'p50': percentile(.5),
                                          'p95': percentile(.95), 'p99': percentile(.99), 'max': max(ordered, default=None)},
                      'duplicates': duplicate[0], 'orderViolations': order_errors[0],
                      'rejections': rejections, 'failures': list(failures),
                      'sendElapsedSeconds': elapsed, 'samples': samples,
                      'inputScheduleCompleted': submitted == count,
                      'note': 'Paused probe never calls recv after joined. Missing probe deliveries are intentional; normal viewers stay in denominator. No claim of reproduced backpressure without server evidence.'}
            (output / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(report['normalLatencyMs']), flush=True)
    finally:
        for client in clients:
            client.close()
        (output / 'config.json').write_text(json.dumps(vars(args), indent=2), encoding='utf-8')
        (output / 'timing.json').write_text(json.dumps({'startedAtEpochSeconds': start, 'endedAtEpochSeconds': time.time()}), encoding='utf-8')


if __name__ == '__main__':
    main()
