"""T6口径测试：退避边界、真实关闭码、跨代去重、固定在线分母，以及重试控制流。"""
import asyncio
from pathlib import Path
import sys
import time
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'load'))
import reconnect as t6


def args():
    """六位观众中三位会重连，每窗口一条消息，便于逐条核对分母。"""
    return SimpleNamespace(connections=6, affected=3, cycles=1, steady_seconds=1, churn_seconds=1,
        rate=1, latency_sample_every=1, probes=1, probe_attempts=3, max_attempts=6,
        recovery_deadline=15, seed=1, slow_connections=0, slow_bytes_per_second=1024)


class BackoffTest(unittest.TestCase):
    def test_exponential_caps_and_thirty_second_ceiling(self):
        """检查1/2/4/8/16/30秒上限，重复失败不能把定时器扩大到无界。"""
        rng = SimpleNamespace(random=lambda: .5)
        self.assertEqual([t6.backoff(i, rng)[0] for i in range(7)], [1000, 2000, 4000, 8000, 16000, 30000, 30000])
        self.assertEqual(t6.backoff(1000, rng)[1], 15000)

    def test_full_jitter_can_be_zero_and_stays_below_cap(self):
        """全抖动覆盖零等待与接近上限，不能变成所有客户端统一延迟。"""
        self.assertEqual(t6.backoff(0, SimpleNamespace(random=lambda: 0)), (1000, 0))
        self.assertLess(t6.backoff(2, SimpleNamespace(random=lambda: .999))[1], 4000)
        with self.assertRaises(ValueError):
            t6.backoff(-1, SimpleNamespace(random=lambda: .5))

    def test_only_real_close_frame_1013_qualifies_as_capacity_rejection(self):
        """TCP关闭、超时和其他关闭码不能被报告成成功观察到准入拒绝。"""
        self.assertEqual(t6.close_code(EOFError('服务端关闭帧:03f5')), 1013)
        self.assertEqual(t6.close_code(EOFError('服务端关闭帧:03e8')), 1000)
        self.assertIsNone(t6.close_code(EOFError('TCP 对端关闭')))
        self.assertIsNone(t6.close_code(OSError('服务端关闭帧:03f5')))


class AccountingTest(unittest.TestCase):
    def setUp(self):
        self.state = t6.State(args())
        self.state.instance = 'instance'
        self.state.text = 'text'
        self.now = time.time_ns() // 1000000 - 10
        for index in range(self.state.plan.total):
            self.state.times[index] = self.now

    def broadcast(self, index, sequence=None):
        return dict(type='danmaku', clientMessageId=f'{self.state.room}:{index:06d}', roomId=self.state.room,
            roomInstanceId='instance', runId='run', text='text', clientSentAt=self.now,
            roomSeq=index + 1 if sequence is None else sequence)

    def peer(self, index, generation=0):
        return SimpleNamespace(index=index, generation=generation, probe=False, intentional=False)

    def complete(self):
        """重连窗口只给固定组收帧，其他两个窗口全收；离线组没有补发也必须通过。"""
        for p, phase in enumerate(self.state.plan.phases):
            index = phase['offset']
            self.state.status[index] = 1
            self.state.sent[p], self.state.duration[p] = 1, 1
            cohort = self.state.stable if phase['churn'] else self.state.args.connections
            for viewer in range(cohort):
                self.state.event(self.peer(viewer), self.broadcast(index))
        self.state.joins = [dict(generation=0, probe=False) for _ in range(6)]
        self.state.probes = [True]
        self.state.recoveries = [dict(allRejoined=True)]
        self.state.planned_disconnects = 3

    def test_offline_viewers_are_not_required_to_replay_history(self):
        """明确离线期间不可靠补发；重连期必达分母仍固定为三位始终在线观众。"""
        self.complete()
        report = self.state.report(None)
        self.assertTrue(report['passed'])
        self.assertEqual(report['phases'][1]['acceptedRequiredDemand'], 3)
        self.assertEqual(report['phases'][1]['allInitialViewerPotentialDemand'], 6)
        self.assertEqual(report['requiredReceived'], 15)

    def test_missing_always_online_viewer_stays_in_denominator(self):
        """少一位固定在线观众就失败，不能拿重连组额外收到的消息抵消缺口。"""
        self.complete()
        self.state.required_receipts[1] -= 1
        self.state.per_viewer[0][1] -= 1
        report = self.state.report(None)
        self.assertFalse(report['passed'])
        self.assertEqual(report['phases'][1]['acceptedRequiredDemand'], 3)
        self.assertEqual(report['phases'][1]['missingAccepted'], 1)

    def test_same_input_across_connection_generations_is_still_duplicate(self):
        """换了socket不清去重表，不能把重复补发当成新的业务消息。"""
        self.state.event(self.peer(3, 0), self.broadcast(0))
        self.state.event(self.peer(3, 1), self.broadcast(0))
        self.assertEqual(self.state.all_receipts[0], 1)
        self.assertEqual(self.state.duplicates, 1)

    def test_wrong_sender_cannot_confirm_and_boolean_is_not_sequence(self):
        """验证接受结果的发送者身份，并排除Python的bool被误当整数序号。"""
        with self.assertRaises(ValueError):
            self.state.event(self.peer(1), dict(type='send_result', clientMessageId=f'{self.state.room}:000000', status='accepted'))
        with self.assertRaises(ValueError):
            self.state.event(self.peer(0), self.broadcast(0, True))

    def test_planned_close_is_not_unexpected_but_stable_failure_is(self):
        """脚本主动关旧连接不报意外；固定组真正的读失败必须保留。"""
        old = self.peer(3)
        old.intentional = True
        self.state.failure(old, 'read', EOFError('planned'))
        self.assertFalse(self.state.failures)
        self.state.failure(self.peer(0), 'read', EOFError('unexpected'))
        self.assertEqual(self.state.failures['read'], 1)

    def test_refusal_cannot_reduce_nominal_required_demand(self):
        """输入被拒绝时仍保留原计划需求，而且本场景不能算通过。"""
        self.complete()
        self.state.status[1] = 2
        self.state.required_receipts[1] = 0
        for viewer in range(3):
            self.state.per_viewer[viewer][1] = 0
        phase = self.state.report(None)['phases'][1]
        self.assertEqual(phase['nominalRequiredDemand'], 3)
        self.assertEqual(phase['acceptedRequiredDemand'], 0)
        self.assertFalse(phase['passed'])


class RetryTest(unittest.IsolatedAsyncioTestCase):
    async def test_real_rejection_outcomes_advance_attempt_and_cap(self):
        """用假的等待只验证重试控制流；真实毫秒等待另在网络归档中核对，不能混为实测。"""
        state = t6.State(args())
        fake = SimpleNamespace(open=AsyncMock(side_effect=EOFError('服务端关闭帧:03f5')),
            server_close_code=1013, close=lambda: None, intentional=False)
        with patch.object(t6, 'ReconnectPeer', return_value=fake), patch.object(t6.asyncio, 'sleep', new_callable=AsyncMock):
            self.assertTrue(await state.retry(6, -1, 0, True))
        self.assertEqual([r['capMs'] for r in state.retries], [1000, 2000, 4000])
        self.assertEqual([r['outcome'] for r in state.retries], ['rejected_1013'] * 3)

    async def test_success_stops_retrying_after_first_join(self):
        """join成功后停止本轮重试，不再多开一个相同逻辑观众的连接。"""
        state = t6.State(args())
        fake = SimpleNamespace(open=AsyncMock(), server_close_code=None, connection_id='new', close=lambda: None)
        with patch.object(t6, 'ReconnectPeer', return_value=fake), patch.object(t6.asyncio, 'sleep', new_callable=AsyncMock):
            self.assertTrue(await state.retry(3, 1, 1))
        self.assertEqual(len(state.retries), 1)
        self.assertIs(state.peers[3], fake)

    async def test_expired_recovery_budget_does_not_start_socket_attempt(self):
        """总恢复时间已耗尽时失败退出，不能继续无界创建连接。"""
        options = args()
        options.recovery_deadline = 0
        state = t6.State(options)
        with patch.object(t6, 'ReconnectPeer') as peer:
            self.assertFalse(await state.retry(3, 1, 1))
            peer.assert_not_called()


if __name__ == '__main__':
    unittest.main()
