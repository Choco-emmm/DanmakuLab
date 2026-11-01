"""诊断日志规则回归：拒绝损坏/缺失/倒序证据，并允许明确审计失败轮而不放宽通过条件。"""
import json
from pathlib import Path
import runpy
import tempfile
import unittest

RULES = runpy.run_path(str(Path(__file__).with_name('hotspot-summary.test.py')))


class DiagnosticEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.config = dict(drainSeconds=1, diagnosticCloseBatchSize=0, diagnosticCloseBatchIntervalMs=100)
        self.report = dict(totalConnections=2, rooms=[dict(phases=[dict(sent=1, accepted=1,
            acceptedDeliveryDemand=2, received=2)])])
        # 这只是日志夹具，不是网络投递成绩；真实诊断另用k6执行。
        self.events = []
        for kind, at, sent, received, requested, complete in (
                ('started', 0, 0, 0, 0, 0), ('input_stopped', 1000, 1, 1, 0, 0),
                ('closing_started', 2000, 1, 2, 0, 0), ('close_requested', 2000, 1, 2, 2, 0),
                ('settled', 2050, 1, 2, 2, 2)):
            self.events.append(dict(type=kind, atMs=at, sent=sent, accepted=sent,
                acceptedDeliveryDemand=sent*2, received=received, closeRequested=requested,
                closeCompleted=complete, closeMode='all', batch=2, firstFailure=None))

    def write(self, escaped=False):
        lines = ['T4_DIAGNOSTIC '+json.dumps(event) for event in self.events]
        if escaped:
            lines = ['time="test" level=info msg='+json.dumps(line) for line in lines]
        (self.folder/'k6-stderr.txt').write_text('\n'.join(lines), encoding='utf-8')

    def verify(self, require_success=True):
        return RULES['verify_diagnostic'](self.folder, self.report, self.config, require_success)

    def test_raw_and_k6_escaped_logs(self):
        for escaped in (False, True):
            self.write(escaped)
            self.assertEqual(len(self.verify()), 5)

    def test_corrupted_log_is_not_silently_skipped(self):
        self.write()
        with (self.folder/'k6-stderr.txt').open('a') as stream:
            stream.write('\nT4_DIAGNOSTIC {broken}')
        self.assertRaises(json.JSONDecodeError, self.verify)

    def test_missing_terminal_event_is_rejected(self):
        self.events.pop()
        self.write()
        self.assertRaises(AssertionError, self.verify)

    def test_backwards_time_is_rejected(self):
        self.events[-1]['atMs'] = 500
        self.write()
        self.assertRaises(AssertionError, self.verify)

    def test_final_counts_must_match_report_even_in_failure_audit(self):
        self.events[-1]['received'] = 3
        self.write()
        self.assertRaises(AssertionError, self.verify, False)

    def test_failure_evidence_does_not_become_a_pass(self):
        for event in self.events[2:]:
            event['received'] = 1
        self.events[-1].update(closeCompleted=0, firstFailure='close_timeout')
        self.report['rooms'][0]['phases'][0]['received'] = 1
        self.write()
        self.assertEqual(self.verify(False)[-1]['firstFailure'], 'close_timeout')
        self.assertRaises(AssertionError, self.verify, True)

    def test_batch_interval_cannot_be_shortened(self):
        self.config.update(diagnosticCloseBatchSize=1, diagnosticCloseBatchIntervalMs=100)
        self.events[-1]['atMs'] = 2200
        for event in self.events:
            event['closeMode'] = 'batched'
        self.events[3].update(batch=1, closeRequested=1)
        second = dict(self.events[3], atMs=2050, closeRequested=2)
        self.events.insert(4, second)
        self.write()
        self.assertRaises(AssertionError, self.verify)


if __name__ == '__main__':
    unittest.main()
