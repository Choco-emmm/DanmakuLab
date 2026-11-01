/**
 * 多个独立JS上下文共享虚拟WebSocket服务器，执行真实hotspot-workers.js。
 * 验证跨进程消息、编号归属、确认晚到和有限收尾；网络成绩仍以真实k6实验为准。
 * 最小堆按到期时间执行回调，避免模拟几万份广播时反复排序整个队列。
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname,'../load/hotspot-workers.js'),'utf8')
  .replace(/^import .*;\r?\n/gm,'').replace('export default function ()','function run()').replace(/export /g,'');

function simulation(workers, settings={}) {
  let now=1800000000000, serial=0, sequence=0;
  const start=now+1000, heap=[], timers=new Map(), sockets=[], contexts=[], reports=[], events=[];
  const less=(a,b)=>a.at<b.at || (a.at===b.at && a.id<b.id);
  function push(item){heap.push(item);let i=heap.length-1;while(i){const p=(i-1)>>1;if(!less(heap[i],heap[p]))break;[heap[i],heap[p]]=[heap[p],heap[i]];i=p;}}
  function pop(){const first=heap[0],last=heap.pop();if(heap.length){heap[0]=last;let i=0;while(true){let j=i*2+1;if(j>=heap.length)break;if(j+1<heap.length&&less(heap[j+1],heap[j]))j++;if(!less(heap[j],heap[i]))break;[heap[i],heap[j]]=[heap[j],heap[i]];i=j;}}return first;}
  function schedule(fn,delay,interval=0){const item={id:++serial,at:now+delay,fn,interval};timers.set(item.id,item);push(item);return item.id;}
  for(let w=0;w<workers;w++){
    const local=[];
    class Metric {constructor(name){this.name=name;}add(){} }
    class Socket {
      constructor(){
        if(settings.createFailure===w && !local.length)throw new Error('construct failure');
        this.readyState=0;this.handlers={};this.id=`c${w}-${local.length}`;local.push(this);sockets.push(this);
        schedule(()=>{if(this.readyState===0){this.readyState=1;this.emit('open');}},settings.late===w?2000:1);
      }
      addEventListener(name,fn){(this.handlers[name]||=[]).push(fn);}
      emit(name,arg={}){for(const fn of this.handlers[name]||[])fn(arg);}
      send(text){
        const input=JSON.parse(text);
        if(input.type==='join'){
          this.room=input.roomId;this.nickname=input.nickname;
          schedule(()=>this.emit('message',{data:JSON.stringify({type:'joined',roomId:this.room,roomInstanceId:'instance',connectionId:this.id})}),1);
        }
        if(input.type!=='send')return;
        const g=Number(input.clientMessageId.split('-').at(-1)),accepted=g%2===0;
        const seq=accepted?++sequence:0;
        // 故意让广播先于accepted回复到达，跨上下文接收者也必须能记录并离线确认。
        schedule(()=>this.emit('message',{data:JSON.stringify({type:'send_result',clientMessageId:input.clientMessageId,
          status:accepted?'accepted':'rejected',roomSeq:seq,reason:accepted?'':'room_rate_limit'})}),20);
        if(accepted)for(const peer of sockets.filter(s=>s.room===this.room)){
          const output={type:'danmaku',clientMessageId:input.clientMessageId,clientSentAt:input.clientSentAt,text:input.text,
            roomId:this.room,roomInstanceId:'instance',runId:'server-run',senderId:this.id,nickname:this.nickname,roomSeq:seq};
          if(settings.foreign && g===0 && peer===sockets[0])output.clientMessageId='foreign-run-0';
          schedule(()=>{if(peer.readyState===1)peer.emit('message',{data:JSON.stringify(output)});},2);
          if(settings.duplicate && g===0 && peer===sockets[0])schedule(()=>peer.emit('message',{data:JSON.stringify(output)}),3);
        }
      }
      close(){if(this.readyState>=2)return;this.readyState=2;if(!settings.noClose)schedule(()=>{this.readyState=3;this.emit('close',{code:1000});},50);}
    }
    const context=vm.createContext({__ENV:{RUN_KEY:'a'.repeat(32),HOT_WORKERS:String(workers),HOT_WORKER_INDEX:String(w),
      HOT_CONNECTIONS:'50',PHASE_SECONDS:'3',START_AT_MS:String(start),DIAGNOSTIC:'true'},
      WebSocket:Socket,Counter:Metric,Gauge:Metric,Trend:Metric,Date:{now:()=>now},
      check:(value,checks)=>Object.values(checks).every(fn=>fn(value)),sleep:s=>{now+=s*1000;},
      console:{log:line=>{if(line.startsWith('HW_RAW '))reports.push(JSON.parse(line.slice(7)));
        if(line.startsWith('HW_EVENT '))events.push(JSON.parse(line.slice(9)));}},
      setTimeout:(fn,delay)=>schedule(fn,delay),setInterval:(fn,delay)=>schedule(fn,delay,delay),
      clearTimeout:id=>timers.delete(id),clearInterval:id=>timers.delete(id)});
    vm.runInContext(source,context);contexts.push(context);
  }
  contexts.forEach(c=>vm.runInContext('run()',c));
  let steps=0;
  while(heap.length){
    const item=pop();if(timers.get(item.id)!==item)continue;
    if(item.at>start+30000)break;
    assert.ok(++steps<200000,'callbacks must remain bounded');now=Math.max(now,item.at);timers.delete(item.id);
    if(item.interval){const next={...item,at:now+item.interval};timers.set(next.id,next);push(next);}
    item.fn();
  }
  return {reports:reports.sort((a,b)=>a.worker-b.worker),events,sockets,timers,contexts};
}

for(const count of [1,5])test(`${count} hot workers share input plan and receive foreign producers`,()=>{
  const run=simulation(count);
  assert.equal(run.reports.length,count);
  assert.equal(run.timers.size,0);
  const all=run.reports.flatMap(r=>r.ledger.map(row=>row[0]));
  assert.equal(all.length,960);assert.equal(new Set(all).size,960);
  assert.ok(run.reports.every(r=>Object.values(r.errors).every(n=>n===0)));
  let receipts=0;
  for(const report of run.reports){
    assert.ok(report.ledger.every(row=>row[0]%count===report.worker));
    assert.equal(report.observations.length,480);
    for(const viewer of report.viewersSeen){
      const bits=Buffer.from(viewer.bits,'hex');
      for(let g=0;g<960;g++){assert.equal(Boolean(bits[g>>3]&(1<<(g&7))),g%2===0);if(g%2===0)receipts++;}
    }
  }
  assert.equal(receipts,24000);
});

test('duplicate deliveries are classified and cannot hide in deduplicated proof',()=>{
  const run=simulation(5,{duplicate:true});
  assert.ok(run.reports[0].errors.duplicates>0);
});

test('foreign run identities cannot set a receipt bitmap bit',()=>{
  const run=simulation(5,{foreign:true});
  assert.ok(run.reports[0].errors.isolation>0);
  assert.equal(Buffer.from(run.reports[0].viewersSeen[0].bits,'hex')[0]&1,0);
});
test('late join is a failure without reducing local observer count',()=>{
  const run=simulation(5,{late:0});
  assert.ok(run.reports[0].errors.errors>0);
  assert.equal(run.reports[0].viewersSeen.length,10);
  assert.equal(run.timers.size,0);
});
test('constructor failure has a finite terminal proof',()=>{
  const run=simulation(5,{createFailure:0});
  assert.equal(run.reports[0].firstFailure,'socket_create');
  assert.equal(run.timers.size,0);
});
test('missing close callbacks fail and late callbacks cannot rewrite terminal evidence',()=>{
  const run=simulation(5,{noClose:true});const n=run.events.length;
  assert.ok(run.reports.every(r=>r.firstFailure==='close_timeout'));
  run.sockets.forEach(s=>s.emit('close'));
  assert.equal(run.events.length,n);assert.equal(run.timers.size,0);
});
