import { WebSocket } from 'k6/websockets';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { check, sleep } from 'k6';

/**
 * 一个VU管理热点房间的一份观众，但接收所有发送进程的弹幕。
 * 1/5进程使用完全相同的身份、发送计划和统计实现；普通房间仍使用原hotspot.js。
 * VU与handleSummary不共享可变内存，所以逐观众证据只在终态输出一份HW_RAW JSON，
 * 父进程捕获后离线核对；不能把accepted塞进消息ID指标标签来传递，造成无界时间序列。
 */
function integer(name, fallback, min, max) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${name}: ${min}..${max}`);
  return value;
}
const workers = integer('HOT_WORKERS', 1, 1, 5);
if (![1, 5].includes(workers)) throw new Error('HOT_WORKERS must be 1 or 5');
const worker = integer('HOT_WORKER_INDEX', 0, 0, workers - 1);
const viewers = integer('HOT_CONNECTIONS', 500, 50, 500);
if (viewers % workers) throw new Error('HOT_CONNECTIONS must divide evenly');
const localViewers = viewers / workers;
const seconds = integer('PHASE_SECONDS', 10, 3, 180);
const start = Number(__ENV.START_AT_MS);
if (!Number.isSafeInteger(start) || start <= 0) throw new Error('Shared START_AT_MS required');
const key = __ENV.RUN_KEY || '';
if (!/^[a-f0-9]{32}$/.test(key)) throw new Error('RUN_KEY must be a shared 32-digit hex token');
const diagnostic = __ENV.DIAGNOSTIC === 'true';
const stall = integer('DIAGNOSTIC_STALL_MS', 0, 0, 2000);
if ((diagnostic && seconds > 10) || (!diagnostic && stall)) throw new Error('Diagnostic options are short-only');
const sampleEvery = 10;
const rates = [0, 20, 100, 200];
const offsets = rates.map((_, p) => rates.slice(0, p).reduce((a, b) => a + b * seconds, 0));
const total = rates.reduce((a, b) => a + b * seconds, 0);
const roomId = `hw-${key}-hot`;
const closeAt = start + 4 * seconds * 1000 + 5000;

/** 全局输入编号只属于一个发送进程；发送连接在该进程本地轮转。 */
export function owner(index, count) { return index % count; }
export function senderIndex(index, count, connections) { return Math.floor(index / count) % connections; }
export function messageId(run, index) { return `hw-${run}-${index}`; }
export function request(run, index, at) {
  const value = { type: 'send', clientMessageId: messageId(run, index), text: '', clientSentAt: at };
  const padding = 256 - JSON.stringify(value).length;
  if (padding < 1 || padding > 200) throw new Error('Request does not fit 256 bytes');
  value.text = 'x'.repeat(padding);
  return value;
}
export function hex(bytes) { return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join(''); }
function phaseOf(index) { for (let p = 3; p >= 0; p--) if (index >= offsets[p]) return p; return 0; }
function plannedCount(p) {
  let n = 0;
  for (let g = offsets[p]; g < offsets[p] + rates[p] * seconds; g++) if (owner(g, workers) === worker) n++;
  return n;
}
const joinedMetric = new Counter('hw_joined');
const errorMetric = new Counter('hw_errors');
const lagMetric = new Trend('hw_send_lag_ms');
const readyMetric = new Gauge('hw_ready_at_ms');
const phaseMetrics = rates.map((r, p) => ({
  sent: new Counter(`hw_p${p}_sent`), lag: new Trend(`hw_p${p}_lag_ms`),
  rate: new Gauge(`hw_p${p}_rate`), received: new Counter(`hw_p${p}_received`),
}));
const thresholds = { hw_joined: [`count==${localViewers}`], hw_errors: ['count==0'], iterations: ['count==1'], checks: ['rate==1'] };
rates.forEach((rate, p) => {
  thresholds[`hw_p${p}_sent`] = [`count==${plannedCount(p)}`];
  if (rate) {
    thresholds[`hw_p${p}_lag_ms`] = ['max<1000'];
    thresholds[`hw_p${p}_rate`] = [`value>=${rate/workers*.95}`, `value<=${rate/workers*1.05}`];
  }
});
export const options = {
  scenarios: { hot: { executor: 'shared-iterations', vus: 1, iterations: 1,
    maxDuration: `${Math.max(60, Math.ceil((start-Date.now())/1000)) + 4*seconds + 30}s`, gracefulStop: '5s' } },
  thresholds, summaryTrendStats: ['avg','min','med','p(95)','p(99)','max'],
};

export default function () {
  // 位图每个观众覆盖全部输入。未发送/被拒绝的位也保留，离线必须为0，不能只导出成功样本。
  const seen = Array.from({ length: localViewers }, () => new Uint8Array(Math.ceil(total/8)));
  const sockets = [], connectionIds = [], lastSequence = new Uint32Array(localViewers);
  const attemptedAt = new Float64Array(total), sentOk = new Uint8Array(total), results = new Uint8Array(total);
  const resultSequence = new Uint32Array(total), resultReasons = new Array(total);
  const observedAt = new Float64Array(total), sequence = new Uint32Array(total), sender = new Uint16Array(total);
  const senderIds = [], senderLookup = new Map();
  // 固定毫秒桶0..120000，超界另计；各工作进程的桶能直接相加，不能平均各进程P99。
  const histograms = rates.map(() => new Uint32Array(120001)), overflow = [0,0,0,0];
  const counts = rates.map(() => ({ sent: 0, accepted: 0, rejected: 0, received: 0, samples: 0, durationMs: 0, lag: 0 }));
  const errors = { errors: 0, duplicates: 0, isolation: 0, order: 0, unexpectedCloses: 0 };
  let opened = 0, ready = 0, readyAt = 0, began = false, stopped = false, closing = false, finished = false;
  let instance = null, serverRun = null, firstFailure = null, phase = 0, cursor = 0;
  let requested = 0, completed = 0, drift = 0, lastTick = 0;
  // 200/s的全局间隔只有5ms。1ms检查使单进程也能覆盖末尾的5ms输入槽；
  // 如果系统实际落后，仍按原绝对计划记录滞后，绝不顺延阶段掩盖失败。
  const tickMillis = 1;
  const requestedSet = new Set(), closedSet = new Set(), timers = [];
  errorMetric.add(0); joinedMetric.add(0);
  phaseMetrics.forEach(m => { m.sent.add(0); m.received.add(0); m.rate.add(0); });

  function event(type, extra = {}) {
    // acceptedOwn和receivedAllSources口径不同，绝不相减后称为本进程“积压”。
    if (!diagnostic && !['ready','input_stopped','closing_started','settled'].includes(type)) return;
    console.log(`HW_EVENT ${JSON.stringify({ type, atMs: Date.now(), worker, phase, ready, opened,
      sentOwn: counts.reduce((s,c)=>s+c.sent,0), acceptedOwn: counts.reduce((s,c)=>s+c.accepted,0),
      receivedAllSources: counts.reduce((s,c)=>s+c.received,0), requested, completed, drift, firstFailure, ...extra })}`);
  }
  function failure(reason, category='errors') {
    errors[category]++; errorMetric.add(1);
    if (!firstFailure) { firstFailure = reason; event('first_failure', { reason }); }
  }
  function stop(reason) {
    if (stopped) return;
    stopped = true; event('input_stopped', { reason });
  }
  function finish(reason) {
    if (finished) return;
    stop(reason);
    finished = true; timers.forEach(clearInterval); timers.forEach(clearTimeout);
    let pending = 0;
    const ledger = [], observations = [];
    for (let g=0; g<total; g++) {
      if (attemptedAt[g]) {
        if (!results[g]) pending++;
        ledger.push([g,attemptedAt[g],sentOk[g],results[g],resultSequence[g],resultReasons[g]||'',senderIndex(g,workers,localViewers)]);
      }
      if (observedAt[g]) observations.push([g,observedAt[g],sequence[g],sender[g]-1]);
    }
    if (pending) failure('missing_send_results');
    if (requested !== localViewers || completed !== localViewers) failure('incomplete_close');
    event('settled', { reason, pending });
    // 终态一次输出结构化证据；正式最大57600个输入，数组/位图大小均由固定场景上限决定。
    // 输出发生在测量与关闭之后，父进程连续排空管道，随后压缩归档原始日志。
    console.log(`HW_RAW ${JSON.stringify({ schemaVersion:1,key,worker,workers,viewers,localViewers,seconds,start,
      roomId,instance,serverRun,readyAt,phaseCounts:counts,errors,firstFailure,pending,
      closeRequested:requested,closeCompleted:completed,drift,connectionIds,senderIds,ledger,observations,
      viewersSeen:seen.map((bits,v)=>({viewer:worker*localViewers+v,bits:hex(bits)})),
      histograms:histograms.map((bins,p)=>({bins:Array.from(bins.entries()).filter(([,n])=>n),overflow:overflow[p]})) })}`);
    check(null, { '本工作进程无校验错误且发送结果齐全':()=>pending===0 && Object.values(errors).every(n=>n===0) });
  }
  function closeAll() {
    if (finished) return;
    stop('input_complete'); closing = true; event('closing_started');
    for (const socket of sockets) {
      requestedSet.add(socket); requested++;
      if (socket.readyState===3) { completed++; continue; }
      try { socket.close(); } catch { failure('close_exception'); }
    }
    if (completed===opened) finish('close_complete');
    else timers.push(setTimeout(()=>{failure('close_timeout');finish('close_timeout');},5000));
  }
  function tick() {
    if (finished || stopped || !began) return;
    const now = Date.now();
    if (lastTick) drift=Math.max(drift,now-lastTick-tickMillis); lastTick=now;
    while (phase<4 && now>=start+(phase+1)*seconds*1000) {
      counts[phase].durationMs=now-(start+phase*seconds*1000);
      phaseMetrics[phase].rate.add(counts[phase].sent*1000/counts[phase].durationMs);
      if (cursor < offsets[phase]+rates[phase]*seconds) { failure('phase_send_lag');stop('phase_send_lag');return; }
      phase++;
      if (phase===4) { stop('input_complete'); return; }
      cursor=offsets[phase]; while(owner(cursor,workers)!==worker) cursor++;
      event('phase_started');
      if (phase===1 && stall) timers.push(setTimeout(()=>{
        if (finished || stopped) return;
        event('injected_stall_started',{durationMs:stall});sleep(stall/1000);event('injected_stall_finished');
      },500));
    }
    if (!rates[phase]) return;
    let burst=0;
    while(cursor<offsets[phase]+rates[phase]*seconds && burst++<100) {
      const planned=start+phase*seconds*1000+(cursor-offsets[phase])*1000/rates[phase];
      if (now<planned) break;
      const g=cursor, v=senderIndex(g,workers,localViewers), lag=now-planned;
      attemptedAt[g]=now;
      try { sockets[v].send(JSON.stringify(request(key,g,now))); sentOk[g]=1; }
      catch { failure('send_exception');stop('send_exception');return; }
      counts[phase].sent++; counts[phase].lag=Math.max(counts[phase].lag,lag);
      phaseMetrics[phase].sent.add(1);phaseMetrics[phase].lag.add(lag);lagMetric.add(lag);
      cursor+=workers;
    }
  }
  function receive(v, data) {
    if (finished) return;
    let e; try { e=JSON.parse(data); } catch { failure('invalid_json');return; }
    if (e.type==='joined') {
      if(connectionIds[v] || e.roomId!==roomId || (instance && e.roomInstanceId!==instance)){failure('invalid_join');return;}
      connectionIds[v]=e.connectionId;instance=e.roomInstanceId;ready++;joinedMetric.add(1);
      if(ready===localViewers){readyAt=Date.now();readyMetric.add(readyAt);event('ready');
        if(readyAt>=start){failure('joined_late');stop('joined_late');}}
      return;
    }
    if(e.type==='pong') return;
    const prefix=`hw-${key}-`, id=e.clientMessageId;
    const g=typeof id==='string' && id.startsWith(prefix) ? Number(id.slice(prefix.length)) : -1;
    if(!Number.isInteger(g)||g<0||g>=total||id!==messageId(key,g)){failure('foreign_identity','isolation');return;}
    const p=phaseOf(g);
    if(e.type==='send_result') {
      if(owner(g,workers)!==worker||senderIndex(g,workers,localViewers)!==v||!attemptedAt[g]||results[g]){failure('invalid_result');return;}
      if(e.status==='accepted'){results[g]=1;resultSequence[g]=e.roomSeq;counts[p].accepted++;}
      else if(e.status==='rejected'){results[g]=2;resultReasons[g]=e.reason;counts[p].rejected++;
        if(e.reason!=='room_rate_limit')failure('unexpected_rejection');}
      else failure('unknown_result');
      return;
    }
    if(e.type!=='danmaku'||e.roomId!==roomId||e.roomInstanceId!==instance||(serverRun&&serverRun!==e.runId)){
      failure('room_or_run_mismatch','isolation');return;}
    serverRun=e.runId;
    if(!Number.isSafeInteger(e.clientSentAt)||e.clientSentAt<start||e.text!==request(key,g,e.clientSentAt).text){failure('invalid_payload');return;}
    const expectedNick=`hw${owner(g,workers)}-v${senderIndex(g,workers,localViewers)}`;
    if(e.nickname!==expectedNick || typeof e.senderId!=='string'){failure('invalid_sender');return;}
    if(observedAt[g]&&(observedAt[g]!==e.clientSentAt||sequence[g]!==e.roomSeq||senderIds[sender[g]-1]!==e.senderId)){
      failure('inconsistent_metadata');return;}
    const byte=g>>3, mask=1<<(g&7);
    // 同一条重复到达也有相同序号；先查位图，避免被混入乱序计数。
    if(seen[v][byte]&mask){failure('duplicate_delivery','duplicates');return;}
    if(!Number.isInteger(e.roomSeq)||e.roomSeq<1||e.roomSeq<=lastSequence[v]){failure('out_of_order','order');return;}
    if(!senderLookup.has(e.senderId)){
      if(senderIds.length>=viewers){failure('sender_table_full');return;}
      senderLookup.set(e.senderId,senderIds.length);senderIds.push(e.senderId);
    }
    observedAt[g]=e.clientSentAt;sequence[g]=e.roomSeq;sender[g]=senderLookup.get(e.senderId)+1;
    lastSequence[v]=e.roomSeq;seen[v][byte]|=mask;counts[p].received++;phaseMetrics[p].received.add(1);
    if((worker*localViewers+v)%sampleEvery===0){
      const elapsed=Date.now()-e.clientSentAt;
      if(elapsed<0){failure('negative_latency');return;}
      counts[p].samples++;
      if(elapsed>120000)overflow[p]++;else histograms[p][elapsed]++;
    }
  }
  function openBatch() {
    if(finished || stopped) return;
    const end=Math.min(opened+20,localViewers);
    while(opened<end){
      const v=opened;let socket;
      try{socket=new WebSocket(__ENV.WS_URL||'ws://127.0.0.1:8085/ws');}
      catch{failure('socket_create');stop('socket_create');return;}
      opened++;sockets.push(socket);
      socket.addEventListener('open',()=>{
        try{socket.send(JSON.stringify({type:'join',roomId,nickname:`hw${worker}-v${v}`}));}
        catch{failure('join_exception');stop('join_exception');}
      });
      socket.addEventListener('message',({data})=>receive(v,data));
      socket.addEventListener('error',()=>{if(!finished){failure('socket_error');stop('socket_error');}});
      socket.addEventListener('close',(e={})=>{
        if(finished||closedSet.has(socket))return;closedSet.add(socket);
        if(requestedSet.has(socket)){completed++;if(completed===opened&&closing)finish('close_complete');}
        else{failure('unexpected_close','unexpectedCloses');stop('unexpected_close');event('unexpected_close',{code:e.code});}
      });
    }
  }
  event('started');openBatch();timers.push(setInterval(openBatch,100));
  timers.push(setTimeout(()=>{
    if(ready!==localViewers){failure('not_ready_at_start');stop('not_ready_at_start');return;}
    began=true;cursor=offsets[0];while(owner(cursor,workers)!==worker)cursor++;
    event('input_started');tick();timers.push(setInterval(tick,tickMillis));
  },Math.max(0,start-Date.now())));
  timers.push(setInterval(()=>{
    if(finished||closing)return;
    for(const socket of sockets)if(socket.readyState===1){try{socket.send('{"type":"ping"}');}catch{failure('heartbeat');}}
  },15000));
  if(diagnostic)timers.push(setInterval(()=>event('sample'),1000));
  // 统一绝对关闭时刻，输入失败时也不提前踢掉观众来缩小其他发送者的投递分母。
  timers.push(setTimeout(closeAll,Math.max(0,closeAt-Date.now())));
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_PATH||'load-results/hotspot-worker-summary.json']:JSON.stringify({schemaVersion:1,
    role:'hot-worker',key,worker,workers,viewers,localViewers,seconds,start,roomId,diagnostic,stall,metrics:data},null,2),
    stdout:`HW worker=${worker}/${workers}; complete; raw proof is in captured stderr\n` };
}
