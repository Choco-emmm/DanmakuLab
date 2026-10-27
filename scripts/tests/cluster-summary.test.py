"""独立读取Redis归档的逐输入、逐观众和毫秒桶；不导入客户端汇总方法。"""
import argparse
import json
import math
from pathlib import Path


def read(folder,name): return json.loads((folder/name).read_text(encoding='utf-8-sig'))


def verify(folder):
    config=read(folder,'config.json'); report=read(folder,'client/summary.json'); raw=read(folder,'client/observations.json')
    run=read(folder,'run-status.json'); process=read(folder,'python-status.json')
    assert run['passed'] and run['cleanupPassed'] and run['collectorErrors']==0 and run['countersMatch']
    assert process['exitCode']==0 and process['captureComplete'] and not process['timedOut']
    assert report['passed'] and report['fatal'] is None and not report['failures']
    assert report['duplicates']==report['orderViolations']==report['protocolErrors']==report['isolationFailures']==0
    assert len(raw['perViewer'])==config['connections']
    assert report['sent']==len(raw['status']) and 0 not in raw['status']
    assert report['accepted']==raw['status'].count(1) and report['rejected']==raw['status'].count(2) and report['unknown']==raw['status'].count(3)
    accepted_ids=[mid for mid,status in zip(raw['messageIds'],raw['status']) if status==1]
    assert None not in accepted_ids and len(accepted_ids)==len(set(accepted_ids))
    for p,phase in enumerate(report['phases']):
        lo,hi=phase['offset'],phase['offset']+phase['planned']; required=phase['requiredIndices']
        counts=[r[p] for r in raw['perViewer']]
        assert phase['perViewer']==counts and phase['allReceived']==sum(counts)==sum(raw['receipts'][lo:hi])
        assert phase['requiredReceived']==sum(counts[i] for i in required)
        assert phase['nominalDemand']==phase['planned']*len(required)
        assert phase['allInitialPotential']==phase['planned']*config['connections']
        assert phase['maxLagMs']<1000
        if phase['name']!='uncertain': assert 19<=phase['actualRate']<=21
        assert phase['accepted']==raw['status'][lo:hi].count(1)
        if phase['name']=='uncertain':
            assert raw['status'][lo:hi]==[3]*phase['planned']
            assert 0<=phase['allReceived']<=phase['planned']*config['connections']
        elif phase['offline']:
            assert phase['accepted']==0 and phase['allReceived']==0
        else:
            assert raw['status'][lo:hi]==[1]*phase['planned']
            assert all(counts[i]==phase['planned'] for i in required)
            samples=sum(counts[i] for i in required[::config['sampleEvery']])
            bins=raw['histograms'][p]
            assert sum(c for v,c in bins)==samples==phase['latencyMs']['samples']
            for key,fraction in (('p50',.5),('p95',.95),('p99',.99)):
                total=0
                for value,count in bins:
                    total+=count
                    if total>=math.ceil(samples*fraction):
                        assert phase['latencyMs'][key]==value; break
            assert phase['latencyMs']['p95']<200 and phase['latencyMs']['p99']<500
    assert report['received']==sum(raw['receipts'])
    for snapshot in read(folder,'server-after.json').values():
        if snapshot.get('expectedStopped'): continue
        values={m['name']:m['measurements'][0]['value'] for m in snapshot['metrics']}
        for key in ('lab.connections','lab.rooms','lab.outbound.bytes','lab.outbound.messages','lab.outbound.slots',
            'lab.outbound.tasks','lab.outbound.writers.active','lab.outbound.writes.inflight','lab.dispatch.bytes',
            'lab.dispatch.messages','lab.dispatch.rooms','lab.dispatch.ready','lab.cluster.publish.messages',
            'lab.cluster.publish.bytes','lab.cluster.receive.messages','lab.cluster.receive.bytes'):
            assert values[key]==0,(key,values[key])
    for line in (folder/'samples.jsonl').read_text(encoding='utf-8-sig').splitlines():
        row=json.loads(line)
        for node in row['nodes'].values():
            if node.get('expectedStopped'): continue
            assert 'error' not in node
            values={m['name']:m['measurements'][0]['value'] for m in node['snapshot']['metrics']}
            for key,bound in (('lab.cluster.publish.messages',256),('lab.cluster.receive.messages',256),
                ('lab.cluster.publish.bytes',1048576),('lab.cluster.receive.bytes',1048576),
                ('lab.outbound.bytes',134217728),('lab.dispatch.bytes',4194304)):
                assert 0<=values[key]<=bound
    if config['scenario']=='node-exit': assert len(report['reconnections'])==config['connections']//2
    print(f"PASS Redis {config['scenario']}: {folder}; received={report['received']}")


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('directory',type=Path)
    verify(parser.parse_args().directory)
