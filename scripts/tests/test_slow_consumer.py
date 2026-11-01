"""慢消费者工具的失败路径回归：使用假 socket/时钟，不依赖运行中的 Java 服务。"""
import base64
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    'slow_consumer', Path(__file__).resolve().parents[1] / 'load' / 'slow-consumer.py')
slow = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(slow)


class Clock:
    """仅由假网络推进秒数，精确检查总预算，避免依赖测试机器调度。"""

    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


class ScriptedSocket:
    """逐字节返回有效握手；可以在握手前或 joined 前停住。"""

    def __init__(self, clock, *, stalled_handshake=False, stalled_join=False):
        self.clock = clock
        self.timeout = None
        self.closed = False
        self.stalled_join = stalled_join
        key = base64.b64encode(b'\x01' * 16).decode()
        accept = base64.b64encode(hashlib.sha1(
            (key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        header = f'HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: {accept}\r\n\r\n'.encode()
        self.header_length = len(header)
        payload = json.dumps({'type': 'joined', 'roomId': 'room'}).encode()
        self.data = bytearray(b'' if stalled_handshake else header)
        if not stalled_handshake and not stalled_join:
            self.data.extend(bytes([129, len(payload)]) + payload)
        self.header_remaining = self.header_length if not stalled_handshake else 0

    def settimeout(self, seconds):
        self.timeout = seconds

    def getsockopt(self, *_args):
        return 1024

    def connect(self, _target):
        pass

    def getsockname(self):
        return ('127.0.0.1', 42000)

    def getpeername(self):
        return ('127.0.0.1', 8081)

    def sendall(self, _payload):
        pass

    def recv(self, length):
        if not self.data:
            self.clock.now += self.timeout
            raise socket.timeout('模拟对端不回复')
        # 加入停顿用例中，握手先消耗两秒，joined 只应该剩下一秒。
        if self.stalled_join and self.header_remaining:
            self.clock.now += 2 / self.header_length
        chunk = bytes(self.data[:length])
        del self.data[:length]
        self.header_remaining -= min(self.header_remaining, len(chunk))
        return chunk

    def close(self):
        self.closed = True


class ClientInitializationTest(unittest.TestCase):
    def construct(self, fake, clock):
        with patch.object(slow.socket, 'socket', return_value=fake), \
                patch.object(slow.time, 'monotonic', clock), \
                patch.object(slow.os, 'urandom', side_effect=lambda n: b'\x01' * n):
            return slow.Client('ws://127.0.0.1:8081/ws', 'test', 'room', lambda *_: None, [])

    def test_handshake_timeout_closes_socket(self):
        """对端一直不回复握手：必须超时退出并关闭尚未登记到 clients 的连接。"""
        clock = Clock()
        fake = ScriptedSocket(clock, stalled_handshake=True)
        with self.assertRaises(TimeoutError):
            self.construct(fake, clock)
        self.assertTrue(fake.closed)
        self.assertAlmostEqual(clock.now, 3)

    def test_handshake_and_join_share_total_deadline(self):
        """握手花两秒后 joined 不回复：总耗时三秒，不能给 joined 再等完整三秒。"""
        clock = Clock()
        fake = ScriptedSocket(clock, stalled_join=True)
        with self.assertRaises(TimeoutError):
            self.construct(fake, clock)
        self.assertTrue(fake.closed)
        self.assertAlmostEqual(clock.now, 3)
        self.assertAlmostEqual(fake.timeout, 1)

    def test_join_success_leaves_reading_under_caller_control(self):
        """成功加入后不自动开读线程，保持 paused 探针原有的停止读取行为。"""
        clock = Clock()
        fake = ScriptedSocket(clock)
        client = self.construct(fake, clock)
        self.assertTrue(client.ready.is_set())
        self.assertIsNone(client.thread)
        self.assertIsNone(client.initialization_deadline)
        self.assertFalse(fake.closed)
        self.assertEqual(fake.timeout, 3)
        self.assertEqual(client.local_endpoint, ('127.0.0.1', 42000))
        self.assertEqual(client.peer_endpoint, ('127.0.0.1', 8081))


class InputAccountingTest(unittest.TestCase):
    def run_experiment(self, fail_send):
        """只发送一条，故意让最后一条成功/失败，验证计划、尝试、提交口径。"""
        class FakeClient:
            def __init__(self, _url, name, _room, callback, _failures, **_kwargs):
                self.name = name
                self.receive_buffer = 1024
                self.callback = callback

            def start_reading(self):
                pass

            def send(self, message):
                if message['type'] == 'send':
                    if fail_send:
                        raise OSError('模拟 sendall 写入失败')
                    # 回复在 send 返回前到达：发送时间必须已经登记，接收验证应通过。
                    self.callback(self.name, dict(message, type='danmaku', roomId=message['clientMessageId'].split(':')[0], roomSeq=1))

            def close(self):
                pass

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'result'
            argv = ['slow-consumer.py', '--mode', 'normal', '--seconds', '1', '--rate', '1', '--output', str(output)]
            with patch.object(slow, 'Client', FakeClient), \
                    patch.object(slow.time, 'sleep'), patch('sys.argv', argv), redirect_stdout(io.StringIO()):
                slow.main()
            return json.loads((output / 'summary.json').read_text(encoding='utf-8'))

    def test_failed_final_send_does_not_complete_schedule(self):
        """失败的最后一次尝试仍归档，但不能算成功提交，更不能标记输入计划完成。"""
        result = self.run_experiment(fail_send=True)
        self.assertEqual(result['plannedInputMessages'], 1)
        self.assertEqual(result['attemptedInputMessages'], 1)
        self.assertEqual(result['submittedInputMessages'], 0)
        self.assertEqual(result['inputMessages'], 0)
        self.assertFalse(result['inputScheduleCompleted'])
        self.assertEqual(result['failures'][0]['operation'], 'send')

    def test_successful_send_counts_once_and_preserves_early_receive(self):
        """正常写入只计一次，且立即到达的回复可以查到发送时间，不会误报未知消息。"""
        result = self.run_experiment(fail_send=False)
        self.assertEqual(result['attemptedInputMessages'], 1)
        self.assertEqual(result['submittedInputMessages'], 1)
        self.assertEqual(result['inputMessages'], 1)
        self.assertTrue(result['inputScheduleCompleted'])
        self.assertEqual(result['normalReceived'], 1)
        self.assertEqual(result['failures'], [])


class SchedulingAccountingTest(unittest.TestCase):
    """用确定的慢发送与时钟复现计划落后，验证工具能暴露自己的输入瓶颈。"""

    def run_experiment(self, *, first_send_delay=0, rate=4):
        clock = Clock()
        input_calls = [0]

        class FakeClient:
            def __init__(self, _url, name, _room, _callback, _failures, **_kwargs):
                self.name = name
                self.receive_buffer = 1024
                self.local_endpoint = ('127.0.0.1', 42000 + len(name))
                self.peer_endpoint = ('127.0.0.1', 8081)
                if name == 'probe':
                    # 建连花5秒；实际提交速率的分母必须从全部 joined 后重新开始。
                    clock.now += 5

            def start_reading(self):
                pass

            def send(self, message):
                if message['type'] == 'send':
                    input_calls[0] += 1
                    if input_calls[0] == 1:
                        # 模拟首条sendall阻塞。后续调度落后应被记录，即便最终输入全提交。
                        clock.now += first_send_delay

            def close(self):
                pass

        def sleep(seconds):
            clock.now += seconds

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'result'
            argv = ['slow-consumer.py', '--mode', 'paused', '--seconds', '1',
                    '--rate', str(rate), '--output', str(output)]
            with patch.object(slow, 'Client', FakeClient), \
                    patch.object(slow.time, 'monotonic', clock), \
                    patch.object(slow.time, 'time', side_effect=lambda: 1000 + clock.now), \
                    patch.object(slow.time, 'time_ns', side_effect=lambda: int((1000 + clock.now) * 10**9)), \
                    patch.object(slow.time, 'sleep', side_effect=sleep), \
                    patch('sys.argv', argv), redirect_stdout(io.StringIO()):
                slow.main()
            return json.loads((output / 'summary.json').read_text(encoding='utf-8'))

    def test_blocked_send_exposes_next_input_lag_without_changing_denominator(self):
        """首条阻塞1.5秒，第二条计划在0.25秒：应记录1250ms滞后，并保留全员计划分母。"""
        result = self.run_experiment(first_send_delay=1.5)
        self.assertAlmostEqual(result['maxSendLagMs'], 1250)
        self.assertEqual(result['submittedInputMessages'], 4)
        self.assertTrue(result['inputScheduleCompleted'])
        self.assertEqual(result['normalExpected'], 40)
        # 假服务没有回复accepted，提交速率仍只按sendall成功数/实际输入循环秒数计算。
        self.assertEqual(result['accepted'], 0)
        self.assertAlmostEqual(result['sendElapsedSeconds'], 1.5)
        self.assertAlmostEqual(result['submittedInputRatePerSecond'], 4 / 1.5)
        self.assertAlmostEqual(result['samples'][0]['atEpochSeconds'], 1006.5)
        self.assertAlmostEqual(result['samples'][0]['seconds'], 1.5)
        self.assertEqual(result['clientEndpoints']['probe']['local'], ['127.0.0.1', 42005])

    def test_on_time_input_has_zero_plan_lag(self):
        """假时钟准确按计划醒来：正常调度不能凭空报告发送滞后。"""
        result = self.run_experiment()
        self.assertEqual(result['maxSendLagMs'], 0)
        self.assertAlmostEqual(result['sendElapsedSeconds'], .75)
        self.assertAlmostEqual(result['submittedInputRatePerSecond'], 4 / .75)

    def test_zero_duration_window_has_unknown_rate_instead_of_division_error(self):
        """只有一条立即完成的输入且时钟没有推进时，实际速率应为null。"""
        result = self.run_experiment(rate=1)
        self.assertEqual(result['submittedInputMessages'], 1)
        self.assertEqual(result['sendElapsedSeconds'], 0)
        self.assertIsNone(result['submittedInputRatePerSecond'])


if __name__ == '__main__':
    unittest.main()
