/**
 * 用真实hotspot.js配合虚拟时钟验证诊断状态机，避免边界测试等待真实网络或60秒截止。
 * Socket/指标是夹具：本文件证明分支、计数与定时器收尾，不作为观众实网收齐的证据。
 * 所有回调按到期时间顺序执行；后续仍需真实k6和已归档JAR的短轮验证。
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../load/hotspot.js'), 'utf8')
  .replace(/^import .*;\r?\n/gm, '').replace('export default function ()', 'function run()')
  .replace(/export /g, '');

function fixture(env = {}, settings = {}) {
  let now = 1800000000000, serial = 0;
  const timers = new Map(), metrics = new Map(), events = [], sockets = [], closes = [];
  const schedule = (fn, delay, interval = 0) => {
    timers.set(++serial, { fn, at: now + delay, interval });
    return serial;
  };
  class Metric {
    constructor(name) { this.values = []; metrics.set(name, this.values); }
    add(value) { this.values.push(value); }
  }
  class Socket {
    constructor() {
      if (sockets.length === settings.createFailureAt) throw new Error('mock construction failure');
      this.readyState = 0; this.listeners = {};
      sockets.push(this);
      schedule(() => { if (this.readyState === 0) { this.readyState = 1; this.emit('open'); } }, settings.openDelay || 1);
    }
    addEventListener(name, fn) { (this.listeners[name] ||= []).push(fn); }
    emit(name, data = {}) { for (const fn of this.listeners[name] || []) fn(data); }
    send(raw) {
      const data = JSON.parse(raw);
      if (data.type === 'join') {
        this.room = data.roomId;
        schedule(() => this.emit('message', { data: JSON.stringify({ type: 'joined', roomId: this.room, roomInstanceId: this.room }) }), 1);
      }
      if (data.type === 'send') {
        schedule(() => this.emit('message', { data: JSON.stringify({ type: 'send_result', status: 'accepted', clientMessageId: data.clientMessageId }) }), 1);
        const leader = sockets.find(s => s.room === this.room);
        const sequence = leader.sequence = (leader.sequence || 0) + 1;
        for (const peer of sockets.filter(s => s.room === this.room)) {
          schedule(() => {
            if (peer.readyState !== 1) return;
            peer.emit('message', { data: JSON.stringify({ type: 'danmaku', clientMessageId: data.clientMessageId,
              roomId: this.room, roomInstanceId: this.room, runId: 'mock', roomSeq: sequence,
              clientSentAt: data.clientSentAt, text: data.text }) });
          }, settings.receiptDelay || 2);
        }
      }
    }
    close() {
      if (this.readyState >= 2) return;
      closes.push({ at: now, socket: sockets.indexOf(this) });
      this.readyState = 2;
      if (!settings.closeTimeout) schedule(() => { this.readyState = 3; this.emit('close'); }, 50);
    }
  }
  const context = vm.createContext({
    __ENV: { NORMAL_ROOMS: '1', NORMAL_CONNECTIONS: '2', HOT_CONNECTIONS: '50',
      PHASE_SECONDS: '2', DRAIN_SECONDS: '1', LATENCY_SAMPLE_EVERY: '1', ...env },
    WebSocket: Socket, Counter: Metric, Gauge: Metric, Trend: Metric,
    Date: { now: () => now },
    console: { log: (...args) => {
      const line = args.join(' ');
      if (line.startsWith('T4_DIAGNOSTIC ')) events.push(JSON.parse(line.slice('T4_DIAGNOSTIC '.length)));
    }, error: () => {} },
    check: (value, checks) => Object.values(checks).every(fn => fn(value)),
    // sleep只用于显式故障注入；推进虚拟时间，定时器回调会在恢复后迟到执行。
    sleep: seconds => { now += seconds * 1000; },
    setTimeout: (fn, delay) => schedule(fn, delay), setInterval: (fn, delay) => schedule(fn, delay, delay),
    clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id),
  });
  vm.runInContext(source, context);
  function advance(milliseconds) {
    const end = now + milliseconds;
    let callbacks = 0;
    while (timers.size) {
      const [id, timer] = [...timers.entries()].sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (timer.at > end) break;
      assert.ok(++callbacks < 100000, 'timer callbacks must remain bounded');
      now = Math.max(now, timer.at);
      timers.delete(id);
      if (timer.interval) timers.set(id, { ...timer, at: now + timer.interval });
      timer.fn();
    }
    now = end;
  }
  return { events, sockets, closes, timers, advance,
    start: () => vm.runInContext('run()', context),
    sum: name => (metrics.get(name) || []).reduce((sum, value) => sum + value, 0),
    max: name => Math.max(0, ...(metrics.get(name) || [])) };
}

test('default path preserves delivery and simultaneous close without diagnostics', () => {
  const run = fixture(); run.start(); run.advance(30000);
  assert.equal(run.sum('t4_joined'), 52);
  for (const name of ['t4_errors', 't4_missing_accepted', 't4_missing_results']) assert.equal(run.sum(name), 0);
  assert.equal(run.closes.length, 52);
  assert.equal(new Set(run.closes.map(item => item.at)).size, 1);
  assert.equal(run.events.length, 0);
  assert.equal(run.timers.size, 0);
});

test('diagnostics reject long phases and batching without opt-in', () => {
  assert.throws(() => fixture({ DIAGNOSTIC: 'true', PHASE_SECONDS: '180' }), /short runs/);
  assert.throws(() => fixture({ DIAGNOSTIC_CLOSE_BATCH_SIZE: '10' }), /requires DIAGNOSTIC/);
});

test('diagnostics reject a batch schedule exceeding its bounded budget', () => {
  assert.throws(() => fixture({ DIAGNOSTIC: 'true', DIAGNOSTIC_CLOSE_BATCH_SIZE: '1',
    DIAGNOSTIC_CLOSE_BATCH_INTERVAL_MS: '5000' }), /batch schedule/);
});

test('diagnostics emit periodic samples, drain before close and preserve terminal evidence', () => {
  const run = fixture({ DIAGNOSTIC: 'true' }); run.start(); run.advance(30000);
  assert.equal(run.sum('t4_errors'), 0);
  assert.ok(run.events.some(event => event.type === 'sample'));
  assert.ok(run.events.length < 120);
  const stopped = run.events.find(event => event.type === 'input_stopped');
  const closing = run.events.find(event => event.type === 'closing_started');
  assert.ok(closing.atMs - stopped.atMs >= 1000);
  const final = run.events.at(-1);
  assert.equal(final.type, 'settled');
  assert.equal(final.closeCompleted, 52);
  assert.equal(final.received, final.acceptedDeliveryDemand);
  assert.ok(run.events.every((event, i) => !i || event.atMs >= run.events[i - 1].atMs));
  assert.equal(run.timers.size, 0);
});

test('drain counts delayed receipts before requesting close', () => {
  const run = fixture({ DIAGNOSTIC: 'true' }, { receiptDelay: 700 }); run.start(); run.advance(30000);
  assert.equal(run.sum('t4_missing_accepted'), 0);
  assert.equal(run.events.at(-1).received, run.events.at(-1).acceptedDeliveryDemand);
  assert.equal(run.timers.size, 0);
});

test('batch close respects configured size and interval', () => {
  const run = fixture({ DIAGNOSTIC: 'true', DIAGNOSTIC_CLOSE_BATCH_SIZE: '10',
    DIAGNOSTIC_CLOSE_BATCH_INTERVAL_MS: '100' }); run.start(); run.advance(30000);
  assert.equal(run.closes.length, 52);
  const groups = new Map();
  for (const item of run.closes) groups.set(item.at, (groups.get(item.at) || 0) + 1);
  assert.equal(groups.size, 6);
  assert.ok([...groups.values()].every(count => count <= 10));
  const times = [...groups.keys()];
  assert.ok(times.slice(1).every((value, i) => value - times[i] === 100));
  assert.equal(run.sum('t4_missing_accepted'), 0);
  assert.equal(run.events.at(-1).closeCompleted, 52);
  assert.equal(run.timers.size, 0);
});

test('early failure preserves first reason and closes created sockets', () => {
  const run = fixture({ DIAGNOSTIC: 'true' }); run.start(); run.advance(1500);
  run.sockets[0].emit('error'); run.advance(30000);
  assert.ok(run.sum('t4_errors') > 0);
  assert.equal(run.closes.length, 52);
  assert.equal(run.events.at(-1).type, 'settled');
  assert.equal(run.events.at(-1).firstFailure, 'socket_error');
  assert.equal(run.timers.size, 0);
});

test('socket creation failure preserves bounded diagnostic settlement', () => {
  const run = fixture({ DIAGNOSTIC: 'true' }, { createFailureAt: 0 }); run.start(); run.advance(30000);
  assert.ok(run.sum('t4_errors') > 0);
  assert.equal(run.events.at(-1).type, 'settled');
  assert.equal(run.events.at(-1).firstFailure, 'socket_create');
  assert.equal(run.timers.size, 0);
});

test('connecting sockets are included in bounded close settlement', () => {
  const run = fixture({ DIAGNOSTIC: 'true', DIAGNOSTIC_CLOSE_BATCH_SIZE: '10',
    DIAGNOSTIC_CLOSE_BATCH_INTERVAL_MS: '100' }, { openDelay: 10000 });
  // 延迟打开10秒并不会触发60秒加入截止。显式在尚未打开时注入连接错误，
  // 才能真正验证CONNECTING连接的关闭分母和有限收尾，而不是错误地期待它自动超时。
  run.start(); run.advance(1500);
  assert.ok(run.sockets.every(socket => socket.readyState === 0));
  run.sockets[0].emit('error'); run.advance(30000);
  assert.ok(run.sum('t4_errors') > 0);
  assert.equal(run.events.at(-1).type, 'settled');
  assert.equal(run.events.at(-1).closeRequested, run.sockets.length);
  assert.equal(run.timers.size, 0);
});

test('missing close callbacks retain timeout evidence and stop diagnostic timers', () => {
  // 模拟关闭握手始终没有回调：必须记录close_timeout，不能误记为正常结束。
  const run = fixture({ DIAGNOSTIC: 'true' }, { closeTimeout: true });
  run.start(); run.advance(30000);
  assert.ok(run.sum('t4_errors') > 0);
  assert.equal(run.events.at(-1).firstFailure, 'close_timeout');
  assert.equal(run.events.at(-1).closeCompleted, 0);
  assert.equal(run.timers.size, 0);
});

test('explicit short diagnostic stall makes the original send-lag gate red-capable', () => {
  // 生产门槛仍是max<1000；本测试制造1100ms停顿，不能通过放宽阈值掩盖。
  assert.throws(() => fixture({ DIAGNOSTIC_STALL_MS: '1100' }), /requires DIAGNOSTIC/);
  const run = fixture({ DIAGNOSTIC: 'true', DIAGNOSTIC_STALL_MS: '1100' });
  run.start(); run.advance(30000);
  assert.ok(run.max('t4_r1_p1_send_lag_ms') >= 1000);
  assert.equal(run.events.filter(event => event.type === 'injected_stall_started').length, 1);
  assert.equal(run.events.at(-1).type, 'settled');
  assert.equal(run.timers.size, 0);
});

test('late close callbacks cannot append events after the terminal cutoff', () => {
  // 关闭截止已结算之后，传输层仍可能迟到回调；不能回填计数或在settled后再输出事件。
  const run = fixture({ DIAGNOSTIC: 'true' }, { closeTimeout: true });
  run.start(); run.advance(30000);
  const count = run.events.length;
  for (const socket of run.sockets) socket.emit('close');
  assert.equal(run.events.length, count);
  assert.equal(run.events.at(-1).type, 'settled');
});
