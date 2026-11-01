"""热点工作进程的独立证据审计：合并发送确认后逐观众核对，而非累加局部accepted×局部人数。

输入是原始k6日志、汇总和服务器快照。失败可审计不等于负载通过；不会回写原始状态。
"""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re

GAUGES = ('lab.connections','lab.rooms','lab.outbound.bytes','lab.outbound.messages','lab.outbound.slots',
          'lab.outbound.tasks','lab.outbound.writers.active','lab.outbound.writes.inflight',
          'lab.dispatch.bytes','lab.dispatch.messages','lab.dispatch.rooms','lab.dispatch.ready')
RATES = (0,20,100,200)


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def tagged(path, tag):
    """支持原始JSON和k6日志的转义JSON；损坏的标记行不能静默跳过。"""
    found = []
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if tag+' ' not in line: continue
        match = re.search(re.escape(tag)+r' (\{.*\})', line)
        if not match: raise ValueError(f'Broken {tag} line')
        payload = match.group(1)
        try: item = json.loads(payload)
        except json.JSONDecodeError: item = json.loads(json.loads('"'+payload+'"'))
        found.append(item)
    return found


def thresholds(summary):
    return [dict(metric=name, rule=rule, values=m['values'])
            for name,m in summary['metrics']['metrics'].items()
            for rule,result in m.get('thresholds',{}).items() if not result['ok']]


def receipt_matrix(raws, accepted, viewers, total):
    """位运算逐观众核对全部输入；缺失和多收分别计数，末尾填充位也不能藏数据。"""
    expected = sum(1 << g for g in accepted)
    actual = missing = unexpected = 0
    ids = set()
    per_worker = []
    for raw in raws:
        rows = []
        for entry in raw['viewersSeen']:
            viewer = entry['viewer']
            if viewer in ids or not 0 <= viewer < viewers: raise ValueError('Duplicate/invalid viewer')
            ids.add(viewer)
            data = bytes.fromhex(entry['bits'])
            if len(data) != (total+7)//8: raise ValueError('Invalid bitmap length')
            bits = int.from_bytes(data,'little')
            actual += bits.bit_count()
            missing += (expected & ~bits).bit_count()
            unexpected += (bits & ~expected).bit_count()
            rows.append(bits)
        per_worker.append(rows)
    if ids != set(range(viewers)): raise ValueError('Missing viewer proof; denominator stays fixed')
    return dict(received=actual, demand=len(accepted)*viewers, missing=missing, unexpected=unexpected,
                workerBitsets=per_worker)


def quantiles(bins, overflow):
    """固定毫秒桶合并后再求分位数；超过120000ms只能报告超界，不能伪造尾延迟。"""
    n = sum(bins.values())+overflow
    result = {'samples':n,'overflowAbove120000ms':overflow}
    for label,q in (('p50',.5),('p95',.95),('p99',.99)):
        target = math.ceil(n*q)
        cumulative = 0
        result[label] = None
        if not n: continue
        for ms,count in sorted(bins.items()):
            cumulative += count
            if cumulative >= target: result[label] = ms; break
        if result[label] is None: result[label] = '>120000'
    return result


def audit(directory):
    cfg = read(directory/'config.json')
    workers, viewers, seconds = cfg['hotWorkers'], cfg['hotConnections'], cfg['phaseSeconds']
    start, key = cfg['startAtMs'], cfg['runKey']
    total = sum(RATES)*seconds
    offsets = [sum(RATES[:p])*seconds for p in range(4)]
    masks = [((1 << (r*seconds))-1) << offsets[p] for p,r in enumerate(RATES)]
    errors = []
    def require(ok, reason):
        if not ok and reason not in errors: errors.append(reason)
    normal_payload = read(directory/'normal/k6-summary.json')
    normal = normal_payload['report']
    require(normal['joined']==cfg['normalRooms']*cfg['normalConnections'], 'normal: joined')
    require(normal['readyAtMs']<start<=normal['startedAtMs'] and normal['startLagMs']<1000, 'normal: readiness')
    require(not thresholds(normal_payload), 'normal: thresholds')
    for name in ('errors','duplicates','orderViolations','isolationFailures','unexpectedCloses','missingAccepted','missingResults'):
        require(normal[name]==0, 'normal: '+name)
    normal_totals = Counter()
    normal_p99 = [[] for _ in range(4)]
    for room in normal['rooms']:
        for p,phase in enumerate(room['phases']):
            require(phase['sent']==2*seconds and phase['accepted']==phase['sent'] and phase['rejected']==0, 'normal: inputs')
            require(phase['received']==phase['accepted']*room['connections'] and phase['missingAccepted']==0, 'normal: receipts')
            require(phase['latencySamples']==phase['accepted']*((room['connections']+9)//10), 'normal: samples')
            for k in ('sent','accepted','rejected','received','acceptedDeliveryDemand'): normal_totals[k]+=phase[k]
            if phase['latencyMs']: normal_p99[p].append(phase['latencyMs']['p(99)'])
    raws, hot_summaries, role_status = [], [], []
    all_ids, instances, server_runs = set(), set(), set()
    accepted, rejected, attempted = {}, set(), set()
    hot_sent = hot_rejected = unknown = 0
    for w in range(workers):
        folder = directory/f'hot-{w}'
        summary = read(folder/'k6-summary.json')
        hot_summaries.append(summary)
        data = tagged(folder/'k6-stderr.txt','HW_RAW')
        if len(data)!=1: raise ValueError('Exactly one terminal raw proof required')
        raw = data[0]; raws.append(raw)
        if (raw['worker'],raw['workers'],raw['key'],raw['viewers'],raw['seconds'],raw['start']) != (w,workers,key,viewers,seconds,start):
            raise ValueError('Worker configuration/identity mismatch')
        if len(raw['connectionIds']) != viewers//workers: raise ValueError('Missing connection identities')
        if len(set(raw['connectionIds'])) != len(raw['connectionIds']) or all_ids.intersection(raw['connectionIds']):
            raise ValueError('Duplicate connection identity')
        all_ids.update(raw['connectionIds']); instances.add(raw['instance']);server_runs.add(raw['serverRun'])
        require(raw['readyAt']<start and raw['readyAt']>0, f'hot-{w}: late readiness')
        require(all(n==0 for n in raw['errors'].values()), f'hot-{w}: client errors {raw["errors"]}')
        require(raw['closeRequested']==raw['closeCompleted']==viewers//workers, f'hot-{w}: close')
        require(not thresholds(summary), f'hot-{w}: thresholds')
        events = tagged(folder/'k6-stderr.txt','HW_EVENT')
        require(events and events[-1]['type']=='settled', f'hot-{w}: terminal')
        require(all(b['atMs']>=a['atMs'] for a,b in zip(events,events[1:])), f'hot-{w}: event time')
        for kind in ('ready','input_stopped','closing_started','settled'):
            require(sum(e['type']==kind for e in events)==1, f'hot-{w}: {kind}')
        role_status.append(dict(role=f'hot-{w}', maxSendLagMs=max(c['lag'] for c in raw['phaseCounts']),
            maxTickDriftMs=raw['drift'], firstFailure=raw['firstFailure'],
            failedThresholds=thresholds(summary), injectionObserved=any(e['type']=='injected_stall_started' for e in events),
            events=events if cfg['diagnostic'] else [e for e in events if e['type']!='sample']))
        own = set()
        for row in raw['ledger']:
            g,at,ok,result,seq,reason,v = row
            if g in own or g in attempted or not 0<=g<total or g%workers!=w: raise ValueError('Duplicate/wrong-owner input')
            own.add(g);attempted.add(g)
            p = max(p for p in range(4) if g>=offsets[p])
            planned = start+p*seconds*1000+(g-offsets[p])*1000/RATES[p]
            require(start+p*seconds*1000<=at<start+(p+1)*seconds*1000, f'hot-{w}: input outside phase')
            require(0<=at-planned<1000, f'hot-{w}: independent send-lag check')
            require(ok==1, f'hot-{w}: send exception')
            if v!=(g//workers)%(viewers//workers): raise ValueError('Wrong sending connection')
            hot_sent+=ok
            if result==1:
                accepted[g]=(at,seq,raw['connectionIds'][v]);require(seq>0,f'hot-{w}: accepted sequence')
            elif result==2:
                rejected.add(g);hot_rejected+=1;require(reason=='room_rate_limit',f'hot-{w}: rejection {reason}')
            else:
                unknown+=1;require(False,f'hot-{w}: unconfirmed input')
        require(own==set(range(w,total,workers)),f'hot-{w}: missing planned ledger entry')
        for p,rate in enumerate(RATES):
            count = raw['phaseCounts'][p]
            phase_rows=[r for r in raw['ledger'] if offsets[p]<=r[0]<offsets[p]+rate*seconds]
            require(count['sent']==sum(r[2] for r in phase_rows) and
                    count['accepted']==sum(r[3]==1 for r in phase_rows) and
                    count['rejected']==sum(r[3]==2 for r in phase_rows),f'hot-{w}: ledger phase counters')
            require(count['sent']==rate*seconds//workers, f'hot-{w}: planned input count')
            if rate:
                actual = count['sent']*1000/count['durationMs'] if count['durationMs'] else 0
                require(rate/workers*.95<=actual<=rate/workers*1.05, f'hot-{w}: actual input rate')
    require(len(instances)==len(server_runs)==1 and None not in instances and None not in server_runs,'hot: common room/source')
    matrix = receipt_matrix(raws,accepted,viewers,total)
    require(matrix['missing']==0 and matrix['unexpected']==0,'hot: per-viewer missing/unexpected receipts')
    hist = [Counter() for _ in RATES]; overflows = [0]*4
    for w,raw in enumerate(raws):
        bits = matrix['workerBitsets'][w]
        expected_ids = set(range(w*(viewers//workers),(w+1)*(viewers//workers)))
        if {v['viewer'] for v in raw['viewersSeen']} != expected_ids: raise ValueError('Viewer belongs to wrong worker')
        union = 0
        for item in bits: union |= item
        observations = {o[0]:o[1:] for o in raw['observations']}
        if len(observations)!=len(raw['observations']) or union != sum(1<<g for g in observations): raise ValueError('Observation matrix mismatch')
        for g,(at,seq,sender_index) in observations.items():
            actual = (at,seq,raw['senderIds'][sender_index])
            require(g in accepted and actual==accepted.get(g),f'hot-{w}: observation metadata mismatch')
        for p in range(4):
            receipts = sum((item & masks[p]).bit_count() for item in bits)
            require(receipts==raw['phaseCounts'][p]['received'], f'hot-{w}: raw receive count')
            selected = [item for item,view in zip(bits,raw['viewersSeen']) if view['viewer']%10==0]
            samples = sum((item & masks[p]).bit_count() for item in selected)
            buckets = raw['histograms'][p]
            require(sum(n for ms,n in buckets['bins'])+buckets['overflow']==samples, f'hot-{w}: sample histogram')
            for ms,n in buckets['bins']:
                if not isinstance(ms,int) or not 0<=ms<=120000 or n<0: raise ValueError('Invalid histogram')
                hist[p][ms]+=n
            overflows[p]+=buckets['overflow']
    client = dict(normal_totals)
    client['sent']+=hot_sent;client['accepted']+=len(accepted);client['rejected']+=hot_rejected
    client['received']+=matrix['received'];client['acceptedDeliveryDemand']+=matrix['demand']
    before,after = (read(directory/name) for name in ('server-before.json','server-after.json'))
    def metrics(snapshot): return {m['name']:m['measurements'][0]['value'] for m in snapshot['metrics']}
    a,b = metrics(before),metrics(after)
    server = {name:b.get(name,0)-a.get(name,0) for name in ('lab.messages.input','lab.messages.accepted','lab.messages.rejected',
        'lab.deliveries.expected','lab.deliveries.enqueued','lab.deliveries.submitted','lab.writes.failed','lab.outbound.dropped','lab.dispatch.discarded')}
    for name,field in (('lab.messages.input','sent'),('lab.messages.accepted','accepted'),('lab.messages.rejected','rejected'),
                       ('lab.deliveries.expected','acceptedDeliveryDemand'),('lab.deliveries.enqueued','acceptedDeliveryDemand'),('lab.deliveries.submitted','received')):
        require(server[name]==client[field], 'server mismatch: '+name)
    for name in ('lab.writes.failed','lab.outbound.dropped','lab.dispatch.discarded'): require(server[name]==0,name)
    cleanup = read(directory/'cleanup-gauges.json')
    require(set(cleanup)==set(GAUGES) and all(v==0 for v in cleanup.values()),'cleanup nonzero/unknown')
    status = read(directory/'run-status.json')
    require(status['collectorErrors']==0,'collector errors')
    require(status.get('allReadyBeforeStart',False),'global readiness')
    roles=['normal']+[f'hot-{w}' for w in range(workers)]
    pids=status['processIds']
    require(set(pids)==set(roles) and len(set(pids.values()))==len(roles),'all independent process identities')
    for role in roles:
        s=read(directory/role/'k6-status.json')
        require(s['processId']==pids.get(role),role+': PID evidence mismatch')
        require(s['exitCode']==0 and not s['timedOut'] and s['captureComplete'] and not s['captureErrors'] and not s['cleanupErrors'],role+': process failure')
    result = dict(schemaVersion=1,evidenceComplete=True,passed=not errors,formalEligible=not cfg['diagnostic'],
        client=client,server=server,hot=dict(accepted=len(accepted),rejected=len(rejected),unknown=unknown,
        received=matrix['received'],demand=matrix['demand'],missing=matrix['missing'],unexpected=matrix['unexpected']),
        hotLatency=[quantiles(hist[p],overflows[p]) for p in range(4)],
        normalP99ByPhase=normal_p99,workers=role_status,cleanup=cleanup,errors=errors,
        note='Global accepted set × all fixed viewers; unknown inputs never become rejection. Percentiles merged from histogram counts.')
    (directory/'audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    parser.add_argument('--evidence-only',action='store_true')
    args=parser.parse_args()
    try:
        result=audit(args.directory)
        print(json.dumps({k:result[k] for k in ('passed','evidenceComplete','client','hot','errors')},ensure_ascii=False))
        raise SystemExit(0 if result['passed'] or args.evidence_only else 1)
    except (OSError,ValueError,KeyError,TypeError,IndexError) as error:
        result=dict(evidenceComplete=False,passed=False,error=f'{type(error).__name__}: {error}')
        (args.directory/'audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False));raise SystemExit(1)
