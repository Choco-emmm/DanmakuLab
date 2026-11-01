"""新版长测归档入口的身份与未知值回归，直接调用真实离线审计函数。"""
import json
from pathlib import Path
import runpy
import tempfile
import unittest

rules=runpy.run_path(str(Path(__file__).with_name('longrun-summary.test.py')))


class LongrunArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.folder=Path(self.temp.name);self.path='D:\\coding\\release.jar'
        self.config=dict(jarSha256='A'*64);self.status=dict(artifactIdentityVerified=True)
        self.put('artifact-identity.json',dict(verified=True,path=self.path,sha256='A'*64,serverPid=123))
        self.put('jar-sha256.json',dict(Hash='A'*64,Path=self.path))
        self.put('cleanup-gauges.json',dict.fromkeys(rules['GAUGES'],0))
        (self.folder/'server-command.txt').write_text('java -jar "'+self.path+'" --server.port=8085')

    def put(self,name,value):
        (self.folder/name).write_text(json.dumps(value),encoding='utf-8')

    def verify(self): rules['verify_strict_evidence'](self.folder,self.config,self.status)

    def test_all_twelve_zero_and_real_artifact_pass(self): self.verify()

    def test_unknown_or_missing_cleanup_does_not_become_zero(self):
        for value in (None,1):
            gauges=dict.fromkeys(rules['GAUGES'],0);gauges['lab.outbound.writes.inflight']=value
            self.put('cleanup-gauges.json',gauges)
            with self.assertRaises(AssertionError):self.verify()
        gauges.pop('lab.outbound.writes.inflight');self.put('cleanup-gauges.json',gauges)
        with self.assertRaises(AssertionError):self.verify()

    def test_other_jar_hash_cannot_identify_running_service(self):
        self.put('jar-sha256.json',dict(Hash='B'*64,Path=self.path))
        with self.assertRaises(AssertionError):self.verify()

    def test_development_process_cannot_borrow_artifact_identity(self):
        (self.folder/'server-command.txt').write_text('java -cp target/classes Application')
        with self.assertRaises(AssertionError):self.verify()


if __name__=='__main__':unittest.main()
