"""使用真实报告入口验证门槛状态措辞，不启动压测，不修改历史结果。"""
import json
from pathlib import Path
import tempfile
import unittest
from hotspot_workers_report import report


class WorkerReportTest(unittest.TestCase):
    def render(self, ready, requested):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            state=dict(testsPassed=True,prerequisitesPassed=ready,formalCompleted=0,runFormal=requested,
                       jarSha256='fixture',rows=[],gateReasons=[],notExecuted=['formal-1-direct'])
            (root/'batch-status.json').write_text(json.dumps(state),encoding='utf-8')
            report(root)
            return (root/'report.md').read_text(encoding='utf-8')

    def test_passed_prerequisites_are_reported_as_decided(self):
        text=self.render(True,False)
        self.assertIn('前置门槛通过。',text)
        self.assertNotIn('尚未形成最终门槛结论',text)

    def test_no_formal_request_does_not_generate_default_long_command(self):
        text=self.render(True,False)
        self.assertIn('本批未请求正式长测',text)
        # 默认复制命令保持短测；正式运行须显式选择，不能让报告暗中扩大工作范围。
        self.assertIn('pwsh -NoProfile -File scripts/run/hotspot-workers.ps1\n',text)

    def test_unpassed_gate_is_not_reported_as_success(self):
        text=self.render(False,True)
        self.assertIn('前置门槛尚未通过',text)


if __name__=='__main__': unittest.main()
