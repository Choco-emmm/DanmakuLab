"""独立合并计数回归，重点防止进程拆分把投递分母缩小，以及未知确认被当作拒绝。"""
from copy import deepcopy
from collections import Counter
import json
from pathlib import Path
import tempfile
import unittest
from hotspot_workers_audit import audit, receipt_matrix, quantiles, tagged, GAUGES, RATES


def fixture(root):
    # 小数据仍走真实审计器文件格式；不运行网络，不将这些人为记录作为实测结果。
    seconds,viewers,workers,start=3,50,5,1800000000000
    key='a'*32; total=sum(RATES)*seconds
    offsets=[sum(RATES[:p])*seconds for p in range(4)]
    accepted={g for g in range(total) if g%2==0}
    sequence={g:i+1 for i,g in enumerate(sorted(accepted))}
    connections=[[f'c{w}-{v}' for v in range(10)] for w in range(workers)]
    def put(path,value):
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(json.dumps(value),encoding='utf-8')
    cfg=dict(hotWorkers=workers,hotConnections=viewers,phaseSeconds=seconds,startAtMs=start,
             runKey=key,normalRooms=1,normalConnections=2,diagnostic=True)
    put(root/'config.json',cfg)
    normal_phases=[dict(sent=6,accepted=6,rejected=0,received=12,acceptedDeliveryDemand=12,
        missingAccepted=0,latencySamples=6,latencyMs={'p(99)':2}) for _ in range(4)]
    normal=dict(joined=2,readyAtMs=start-1000,startedAtMs=start,startLagMs=0,
        rooms=[dict(connections=2,phases=normal_phases)])
    normal.update(dict.fromkeys(('errors','duplicates','orderViolations','isolationFailures','unexpectedCloses','missingAccepted','missingResults'),0))
    put(root/'normal/k6-summary.json',dict(report=normal,metrics={'metrics':{}}))
    child=dict(exitCode=0,timedOut=False,captureComplete=True,captureErrors=[],cleanupErrors=[],processId=100)
    put(root/'normal/k6-status.json',child)
    bits=sum(1<<g for g in accepted).to_bytes((total+7)//8,'little').hex()
    for w in range(workers):
        ledger=[];obs=[];counts=[];hist=[]
        for p,rate in enumerate(RATES):
            indexes=range(offsets[p],offsets[p]+rate*seconds)
            own=[g for g in indexes if g%workers==w]
            ac=sum(g in accepted for g in own)
            count=sum(g in accepted for g in indexes)
            counts.append(dict(sent=len(own),accepted=ac,rejected=len(own)-ac,received=count*10,
                               samples=count,durationMs=3000,lag=0))
            hist.append(dict(bins=[[2,count]] if count else [],overflow=0))
            for g in indexes:
                at=start+p*3000+(g-offsets[p])*1000/rate
                if g in accepted:
                    owner=g%workers;v=(g//workers)%10
                    obs.append([g,at,sequence[g],owner*10+v])
                if g%workers==w:
                    ledger.append([g,at,1,1 if g in accepted else 2,sequence.get(g,0),
                                   '' if g in accepted else 'room_rate_limit',(g//workers)%10])
        raw=dict(worker=w,workers=workers,key=key,viewers=viewers,seconds=seconds,start=start,
            connectionIds=connections[w],instance='instance',serverRun='run',readyAt=start-1000,
            errors={'errors':0},closeRequested=10,closeCompleted=10,phaseCounts=counts,drift=0,
            firstFailure=None,ledger=ledger,observations=obs,senderIds=sum(connections,[]),
            viewersSeen=[dict(viewer=w*10+v,bits=bits) for v in range(10)],histograms=hist)
        events=[dict(type=k,atMs=start+t) for k,t in (('ready',-1000),('input_stopped',12000),
                ('closing_started',17000),('settled',17050))]
        text='\n'.join('HW_EVENT '+json.dumps(e) for e in events)+'\nHW_RAW '+json.dumps(raw)
        folder=root/f'hot-{w}';folder.mkdir()
        (folder/'k6-stderr.txt').write_text(text,encoding='utf-8')
        put(folder/'k6-summary.json',dict(metrics={'metrics':{}}))
        put(folder/'k6-status.json',dict(child,processId=101+w))
    values=dict.fromkeys(GAUGES,0)
    values.update({'lab.messages.input':total+24,'lab.messages.accepted':len(accepted)+24,
        'lab.messages.rejected':total-len(accepted),'lab.deliveries.expected':len(accepted)*viewers+48,
        'lab.deliveries.enqueued':len(accepted)*viewers+48,'lab.deliveries.submitted':len(accepted)*viewers+48})
    put(root/'server-before.json',dict(metrics=[dict(name=k,measurements=[dict(value=0)]) for k in values]))
    put(root/'server-after.json',dict(metrics=[dict(name=k,measurements=[dict(value=v)]) for k,v in values.items()]))
    put(root/'cleanup-gauges.json',dict.fromkeys(GAUGES,0))
    put(root/'run-status.json',dict(collectorErrors=0,allReadyBeforeStart=True,
        processIds=dict(normal=100,**{f'hot-{w}':101+w for w in range(5)})))


class WorkerAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);fixture(self.root)

    def mutate_raw(self,w,change):
        path=self.root/f'hot-{w}/k6-stderr.txt'
        raw=tagged(path,'HW_RAW')[0];change(raw)
        events=tagged(path,'HW_EVENT')
        path.write_text('\n'.join('HW_EVENT '+json.dumps(e) for e in events)+'\nHW_RAW '+json.dumps(raw),encoding='utf-8')

    def test_cross_process_broadcast_can_precede_sender_confirmation(self):
        # 接收位图与独立发送台账是分开导出的，不需要在接收时先知道其他进程的accepted。
        result=audit(self.root)
        self.assertTrue(result['passed'],result['errors'])
        self.assertEqual(result['hot']['received'],480*50)
        self.assertEqual(result['hotLatency'][1]['samples'],30*5)

    def test_five_processes_two_viewers_each_have_fifty_expected_deliveries(self):
        raws=[dict(viewersSeen=[dict(viewer=w*2+v,bits='1f') for v in range(2)]) for w in range(5)]
        result=receipt_matrix(raws,set(range(5)),10,5)
        self.assertEqual((result['received'],result['demand']),(50,50))
        raws[0]['viewersSeen'][0]['bits']='1e'
        result=receipt_matrix(raws,set(range(5)),10,5)
        self.assertEqual((result['received'],result['demand'],result['missing']),(49,50,1))

    def test_missing_viewer_cannot_shrink_denominator(self):
        self.mutate_raw(0,lambda r:r['viewersSeen'].pop())
        self.assertRaises(ValueError,audit,self.root)

    def test_unconfirmed_input_cannot_be_reclassified_as_rejected(self):
        self.mutate_raw(0,lambda r:r['ledger'][0].__setitem__(3,0))
        self.assertFalse(audit(self.root)['passed'])

    def test_wrong_owner_or_duplicate_publication_is_rejected(self):
        self.mutate_raw(0,lambda r:r['ledger'].append(deepcopy(r['ledger'][0])))
        self.assertRaises(ValueError,audit,self.root)

    def test_foreign_sender_metadata_is_not_success(self):
        self.mutate_raw(1,lambda r:r['observations'][0].__setitem__(3,1))
        self.assertFalse(audit(self.root)['passed'])

    def test_duplicate_counter_still_fails_even_when_bitmap_is_deduplicated(self):
        self.mutate_raw(2,lambda r:r['errors'].update(duplicates=1))
        self.assertFalse(audit(self.root)['passed'])

    def test_missing_worker_file_is_not_a_pass(self):
        (self.root/'hot-4/k6-summary.json').unlink()
        self.assertRaises(OSError,audit,self.root)

    def test_threshold_failure_is_preserved(self):
        p=self.root/'hot-0/k6-summary.json'
        p.write_text(json.dumps(dict(metrics={'metrics':{'lag':{'values':{'max':1500},'thresholds':{'max<1000':{'ok':False}}}}})))
        self.assertFalse(audit(self.root)['passed'])

    def test_late_readiness_fails_without_moving_shared_start(self):
        self.mutate_raw(3,lambda r:r.update(readyAt=r['start']+1))
        self.assertFalse(audit(self.root)['passed'])

    def test_early_process_exit_is_not_hidden_by_complete_receive_matrix(self):
        p=self.root/'hot-2/k6-status.json';status=json.loads(p.read_text())
        status['exitCode']=7;p.write_text(json.dumps(status))
        self.assertFalse(audit(self.root)['passed'])

    def test_damaged_log_cannot_be_skipped(self):
        p=self.root/'hot-1/k6-stderr.txt'
        p.write_text(p.read_text()+'\nHW_EVENT {broken')
        self.assertRaises(ValueError,audit,self.root)

    def test_missing_close_callback_remains_failure_after_receipts_complete(self):
        self.mutate_raw(2,lambda r:r.update(closeCompleted=9))
        self.assertFalse(audit(self.root)['passed'])

    def test_missing_gauge_is_unknown_instead_of_zero(self):
        p=self.root/'cleanup-gauges.json';gauges=json.loads(p.read_text())
        gauges.pop(GAUGES[0]);p.write_text(json.dumps(gauges))
        self.assertFalse(audit(self.root)['passed'])

    def test_process_identity_cannot_be_borrowed_from_other_worker(self):
        p=self.root/'hot-2/k6-status.json';status=json.loads(p.read_text())
        status['processId']=101;p.write_text(json.dumps(status))
        self.assertFalse(audit(self.root)['passed'])

    def test_histograms_merge_counts_instead_of_averaging_quantiles(self):
        q=quantiles(Counter({1:99,100:1}),0)
        self.assertEqual((q['p50'],q['p99']),(1,1))
        self.assertEqual(quantiles(Counter({1:1}),99)['p99'],'>120000')


if __name__=='__main__':unittest.main()
