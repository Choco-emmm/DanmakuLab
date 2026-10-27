"""离线验证双节点统计的错误分支，不能用额外接收或更换来源身份掩盖错误。"""
import sys
from pathlib import Path
from types import SimpleNamespace
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'load'))
from cluster import State


class ClusterAccountingTest(unittest.TestCase):
    def setUp(self):
        self.state=State(SimpleNamespace(connections=4,urls=['ws://localhost','ws://localhost'],sample_every=2),Path('.'))
        s=self.state
        s.inputs=1; s.times[0]=100; s.origins[0]='node-a'; s.text='ok'; s.input_phase[0]=0
        s.senders[0]=0
        s.phases=[dict(requiredIndices=[1,3])]; s.sampled=[{1}]
        from longrun import Histogram
        s.hist=[Histogram()]; s.per_viewer=[[0] for _ in range(4)]

    def event(self,**changes):
        return dict(type='danmaku',clientMessageId=self.state.room+':000000',roomId=self.state.room,
            clientSentAt=100,originNodeId='node-a',runId='run',roomInstanceId='source-room',roomSeq=1,
            messageId='id',text='ok',**changes)

    def test_cross_node_identity_is_preserved(self):
        """所有观众看到的同一输入必须有同一messageId，不接受接收节点重新生成身份。"""
        self.state.sampled=[set()]
        self.state.event(SimpleNamespace(index=0),self.event())
        changed=self.event(); changed['messageId']='rewritten'
        with self.assertRaises(ValueError): self.state.event(SimpleNamespace(index=2),changed)

    def test_duplicate_cannot_increase_receipt(self):
        """同一观众重复收到同一输入，只计一次并明确报重复。"""
        self.state.sampled=[set()]
        peer=SimpleNamespace(index=0)
        self.state.event(peer,self.event()); self.state.event(peer,self.event())
        self.assertEqual(self.state.receipts[0],1); self.assertEqual(self.state.duplicates,1)

    def test_odd_stable_cohort_has_sampled_viewer(self):
        """节点B恰为奇数索引时仍从必达组内抽样，不能用全局取模导致延迟样本为零。"""
        import time
        self.state.times[0]=time.time_ns()//1000000
        event=self.event(); event['clientSentAt']=self.state.times[0]
        self.state.event(SimpleNamespace(index=1),event)
        self.assertEqual(self.state.hist[0].count,1)

    def test_wrong_origin_is_protocol_error(self):
        """不能把来自B的广播当成A发送的那条。"""
        event=self.event(); event['originNodeId']='node-b'
        with self.assertRaises(ValueError): self.state.event(SimpleNamespace(index=0),event)

    def test_unknown_is_not_rejection_or_acceptance(self):
        """发布结果未知有独立状态，不能被汇总成正常成功。"""
        self.state.event(SimpleNamespace(index=0),dict(type='send_result',clientMessageId=self.state.room+':000000',status='unknown'))
        self.assertEqual(self.state.status[0],3)

    def test_wrong_viewer_cannot_confirm_another_senders_input(self):
        """关联ID正确也不能证明回复路由正确；错发确认不得占用accepted状态或掩盖缺失确认。"""
        confirmation=dict(type='send_result',clientMessageId=self.state.room+':000000',status='accepted')
        with self.assertRaisesRegex(ValueError,'确认返回给了错误的发送者'):
            self.state.event(SimpleNamespace(index=2),confirmation)
        self.assertEqual(self.state.status[0],0)
        self.state.event(SimpleNamespace(index=0),confirmation)
        self.assertEqual(self.state.status[0],1)

    def test_partial_window_keeps_failure_report_instead_of_dividing_by_zero(self):
        """发送中途异常时窗口未完成，仍须生成明确失败的报告，不能在收尾再次异常而丢失证据。"""
        self.state.args.scenario='steady'
        self.state.phases=[dict(name='partial',offset=0,planned=1,requiredIndices=[0,1,2,3],offline=False,
                               beganAt=0,endedAt=None,actualSeconds=0,maxLagMs=0)]
        report=self.state.report('send failed')
        self.assertFalse(report['passed'])
        self.assertEqual(report['fatal'],'send failed')


if __name__=='__main__': unittest.main()
