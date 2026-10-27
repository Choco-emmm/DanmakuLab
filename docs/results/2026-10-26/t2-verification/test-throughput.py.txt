"""T2口径测试：真实接收去重、确认身份、固定分母、跨进程元数据及直方图合并。"""
import importlib.util
import json
from pathlib import Path
import sys
import time
from types import SimpleNamespace
import unittest

# 脚本直接运行时load目录在sys.path；离线测试显式加入，复用同一网络编解码而不复制。
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'load'))
import throughput as t2


def arguments():
    return SimpleNamespace(rates=[1, 2, 3, 4], seconds=1, warmup_seconds=1, connections=4,
        workers=2, room='t2-test', latency_sample_every=1, slow_connections=0, slow_bytes_per_second=1024)


class PlanAndHistogramTest(unittest.TestCase):
    def test_phase_boundaries_keep_warmup_and_measurement_separate(self):
        plan = t2.Plan([1, 2, 3, 4], 1, 1)
        self.assertEqual(plan.total, 20)
        self.assertEqual(list(plan.phase_by_input[:6]), [0, 0, 1, 1, 1, 1])
        self.assertEqual(list(plan.measured[:6]), [0, 1, 0, 0, 1, 1])

    def test_complete_request_is_256_utf8_bytes(self):
        event = t2.message('t2-test', 123, 1790000000000)
        self.assertEqual(len(json.dumps(event, separators=(',', ':')).encode()), 256)
        self.assertLessEqual(len(event['text']), 200)

    def test_merge_uses_samples_not_average_of_worker_quantiles(self):
        fast, slow = t2.Histogram(), t2.Histogram()
        for _ in range(1000):
            fast.add(1)
        slow.add(1000)
        report = t2.merge_histograms([t2.histogram_data(fast), t2.histogram_data(slow)])
        self.assertEqual(report['samples'], 1001)
        self.assertEqual(report['p99'], 1)
        self.assertEqual(report['max'], 1000)

    def test_histogram_merge_keeps_real_overflow_maximum_and_total(self):
        one, two = t2.Histogram(), t2.Histogram()
        one.add(130000)
        two.add(2)
        report = t2.merge_histograms([t2.histogram_data(one), t2.histogram_data(two)])
        self.assertEqual(report['overflowAbove120000ms'], 1)
        self.assertEqual(report['max'], 130000)
        self.assertEqual(report['avg'], 65001)


class AccountingTest(unittest.TestCase):
    def setUp(self):
        self.args = arguments()
        self.state = t2.WorkerState(self.args, 0, 0, 2)
        self.state.instance = 'instance'
        self.peer = SimpleNamespace(index=1)
        self.timestamp = time.time_ns() // 1000000 - 10
        self.identity = 't2-test:000001'

    def broadcast(self, **changes):
        event = dict(type='danmaku', clientMessageId=self.identity, roomId='t2-test',
                     roomInstanceId='instance', runId='run', roomSeq=1,
                     clientSentAt=self.timestamp, text=t2.message('t2-test', 1, self.timestamp)['text'])
        event.update(changes)
        return event

    def test_duplicate_is_not_counted_as_second_receipt(self):
        self.state.event(self.peer, self.broadcast())
        self.state.event(self.peer, self.broadcast())
        self.assertEqual(self.state.receipts[1], 1)
        self.assertEqual(self.state.duplicates, 1)
        self.assertEqual(self.state.per_viewer[1][1], 1)

    def test_wrong_observer_cannot_confirm_another_sender(self):
        with self.assertRaises(ValueError):
            self.state.event(SimpleNamespace(index=0), dict(type='send_result',
                clientMessageId=self.identity, status='accepted'))

    def test_changed_room_or_body_or_boolean_sequence_is_rejected(self):
        for change in ({'roomId': 'other'}, {'text': 'tampered'}, {'roomSeq': True}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.state.event(self.peer, self.broadcast(**change))

    def test_warmup_rejection_is_not_mixed_into_measured_reasons(self):
        self.state.event(SimpleNamespace(index=0), dict(type='send_result',
            clientMessageId='t2-test:000000', status='rejected', reason='room_rate_limit'))
        self.assertEqual(self.state.rejection_reasons[0]['room_rate_limit'], 1)
        self.assertEqual(sum(self.state.rejection_reasons[1].values()), 0)


class SummaryTest(unittest.TestCase):
    def complete_reports(self):
        args = arguments()
        states = [t2.WorkerState(args, worker, worker * 2, worker * 2 + 2) for worker in range(2)]
        now = time.time_ns() // 1000000 - 10
        for state in states:
            state.instance, state.run_id, state.joined = 'instance', 'run', 2
            state.ready_at, state.started_at = 9, 10.01
            state.measure_durations = [1] * 4
            for index in range(state.plan.total):
                for viewer in range(state.begin, state.end):
                    state.event(SimpleNamespace(index=viewer), dict(type='danmaku', roomId=args.room,
                        roomInstanceId='instance', runId='run', clientMessageId=f'{args.room}:{index:06d}',
                        roomSeq=index + 1, clientSentAt=now, text=t2.message(args.room, index, now)['text']))
        states[0].status[:] = bytes([1]) * states[0].plan.total
        states[0].sent = states[0].plan.total
        return args, [state.report(None) for state in states], [dict(worker=i, exitCode=0) for i in range(2)]

    def test_complete_shards_pass_and_have_all_four_viewers(self):
        args, reports, processes = self.complete_reports()
        result = t2.summarize(args, reports, processes, 10, None)
        self.assertTrue(result['toolValid'])
        self.assertTrue(result['allPhasesSloPassed'])
        self.assertEqual(result['phases'][3]['perViewerReceived'], [4, 4, 4, 4])

    def test_missing_viewer_is_not_removed_from_denominator(self):
        args, reports, processes = self.complete_reports()
        reports[1]['receipts'][1] -= 1
        reports[1]['perViewer'][1][1] -= 1
        result = t2.summarize(args, reports, processes, 10, None)
        self.assertTrue(result['toolValid'])
        self.assertEqual(result['phases'][0]['acceptedDeliveryDemand'], 4)
        self.assertEqual(result['phases'][0]['missingAccepted'], 1)
        self.assertFalse(result['phases'][0]['candidateSloPassed'])

    def test_cross_process_timestamp_mismatch_invalidates_tool(self):
        args, reports, processes = self.complete_reports()
        reports[1]['timestamps'][1] += 1
        self.assertFalse(t2.summarize(args, reports, processes, 10, None)['toolValid'])

    def test_missing_confirmation_cannot_shrink_accepted_demand_to_fake_success(self):
        args, reports, processes = self.complete_reports()
        reports[0]['status'][1] = 0
        result = t2.summarize(args, reports, processes, 10, None)
        self.assertFalse(result['toolValid'])
        self.assertFalse(result['allPhasesSloPassed'])

    def test_incomplete_process_reports_produce_explicit_failure(self):
        args, reports, processes = self.complete_reports()
        result = t2.summarize(args, reports[:1], processes, 10, 'worker failed')
        self.assertFalse(result['toolValid'])
        self.assertEqual(result['fatal'], 'worker failed')


if __name__ == '__main__':
    unittest.main()
