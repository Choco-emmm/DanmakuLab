"""长测工具口径回归：分片字节流、掩码、固定空间延迟统计、重复和正常观众分母。"""
import importlib.util
from pathlib import Path
import struct
import time
from types import SimpleNamespace
import unittest

spec = importlib.util.spec_from_file_location('longrun', Path(__file__).resolve().parents[1] / 'load/longrun.py')
longrun = importlib.util.module_from_spec(spec)
spec.loader.exec_module(longrun)


class CodecTest(unittest.TestCase):
    def test_server_frame_can_arrive_in_fragments_and_leave_next_frame(self):
        payload = b'x' * 300
        data = bytes([129, 126]) + struct.pack('!H', len(payload)) + payload
        buffer = bytearray(data[:17])
        self.assertIsNone(longrun.extract(buffer))
        buffer.extend(data[17:] + bytes([129, 1]) + b'y')
        self.assertEqual(longrun.extract(buffer), (1, payload))
        self.assertEqual(longrun.extract(buffer), (1, b'y'))
        self.assertEqual(buffer, b'')

    def test_client_frame_is_masked_and_recovers_original_payload(self):
        payload = b'x' * 256
        result = longrun.frame(payload)
        self.assertEqual(result[:2], bytes([129, 254]))
        self.assertEqual(struct.unpack('!H', result[2:4])[0], 256)
        mask = result[4:8]
        self.assertEqual(bytes(value ^ mask[i % 4] for i, value in enumerate(result[8:])), payload)

    def test_invalid_server_frame_cannot_be_silently_counted(self):
        with self.assertRaises(ValueError):
            longrun.extract(bytearray([129, 128]))
        with self.assertRaises(ValueError):
            longrun.extract(bytearray([1, 0]))

    def test_histogram_keeps_overflow_and_exact_small_quantiles(self):
        histogram = longrun.Histogram()
        for _ in range(99):
            histogram.add(2)
        histogram.add(130000)
        report = histogram.report()
        self.assertEqual(report['p99'], 2)
        self.assertEqual(report['max'], 130000)
        self.assertEqual(report['overflowAbove120000ms'], 1)
        with self.assertRaises(ValueError):
            histogram.add(-1)


class AccountingTest(unittest.TestCase):
    def setUp(self):
        args = SimpleNamespace(connections=3, slow_connections=1, seconds=1, warmup_seconds=0,
                               rate=1, scenario='T3', slow_bytes_per_second=1024, latency_sample_every=1)
        self.state = longrun.State(args)
        self.state.instance, self.state.text = 'instance', 'text'
        self.state.times[0] = time.time_ns() // 1000000 - 10
        self.state.sent = 1
        self.state.measured_start, self.state.measured_end = 1, 2
        self.slow = SimpleNamespace(index=0, slow=True, measured_bytes=0, receive_buffer=1024, alive=False)
        self.first = SimpleNamespace(index=1, slow=False)
        self.second = SimpleNamespace(index=2, slow=False)
        self.identity = self.state.room + ':000000'

    def receive(self, peer, sequence=1):
        self.state.event(peer, dict(type='danmaku', clientMessageId=self.identity, roomId=self.state.room,
                                   roomInstanceId='instance', runId='run', roomSeq=sequence,
                                   text='text', clientSentAt=self.state.times[0]))

    def test_missing_normal_viewer_stays_in_denominator_and_duplicate_not_counted(self):
        self.state.event(self.first, dict(type='send_result', clientMessageId=self.identity, status='accepted'))
        self.receive(self.first)
        self.receive(self.first)
        report = self.state.report([self.slow], None)
        self.assertEqual(report['normalExpected'], 2)
        self.assertEqual(report['normalReceived'], 1)
        self.assertEqual(report['normalMissingAccepted'], 1)
        self.assertEqual(report['duplicates'], 1)
        self.assertFalse(report['checks']['normalComplete'])

    def test_sender_confirmation_cannot_come_from_other_viewer(self):
        with self.assertRaises(ValueError):
            self.state.event(self.second, dict(type='send_result', clientMessageId=self.identity, status='accepted'))
        self.assertEqual(self.state.status[0], 0)

    def test_boolean_is_not_a_room_sequence(self):
        with self.assertRaises(ValueError):
            self.receive(self.first, True)


if __name__ == '__main__':
    unittest.main()
