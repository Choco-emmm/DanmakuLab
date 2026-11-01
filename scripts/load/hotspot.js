import { WebSocket } from 'k6/websockets';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { check, sleep } from 'k6';

/**
 * T4 热点隔离基线：每个进程内用一个 VU 协调所负责的房间。
 * ROLE=combined 为原来的全部房间；normal/hot 分别只连接普通/热点房间，由运行入口并行启动。
 * 分进程时 START_AT_MS 指定共同的 Unix 毫秒起点，各阶段按绝对时间对齐，不能各自加入后就发。
 * 所有阶段保持同一批连接，不通过踢掉观众改善完整率。发送者也属于观众，轮流发送。
 * 分别报告“全部输入的投递需求”和“已接受输入的投递需求”，限流拒绝不能冒充网络丢失。
 * 本脚本只测现有实现，不实现服务端房间队列、公平调度，也不自动调整服务端限流。
 */
function integer(name, fallback, min, max) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${name}: expected ${min}..${max}`);
  return value;
}

const normalRooms = integer('NORMAL_ROOMS', 10, 1, 10);
const normalConnections = integer('NORMAL_CONNECTIONS', 50, 2, 100);
const hotConnections = integer('HOT_CONNECTIONS', 500, 2, 1500);
const seconds = integer('PHASE_SECONDS', 30, 2, 180);
const drainSeconds = integer('DRAIN_SECONDS', 5, 1, 30);
const sampleEvery = integer('LATENCY_SAMPLE_EVERY', 10, 1, 100);
const diagnostic = __ENV.DIAGNOSTIC === 'true';
const closeBatchSize = integer('DIAGNOSTIC_CLOSE_BATCH_SIZE', 0, 0, 1500);
const closeBatchIntervalMs = integer('DIAGNOSTIC_CLOSE_BATCH_INTERVAL_MS', 100, 10, 5000);
// 只供短诊断显式注入一次VU停顿，验证发送滞后门槛确实能失败；正常运行必须为0。
const diagnosticStallMs = integer('DIAGNOSTIC_STALL_MS', 0, 0, 2000);
if (diagnostic && seconds > 10) throw new Error('DIAGNOSTIC is limited to short runs of at most 10 seconds per phase');
if (!diagnostic && (closeBatchSize !== 0 || closeBatchIntervalMs !== 100 || diagnosticStallMs !== 0)) throw new Error('Diagnostic configuration requires DIAGNOSTIC=true');
const role = __ENV.ROLE || 'combined';
if (!['combined', 'normal', 'hot'].includes(role)) throw new Error('ROLE must be combined, normal or hot');
const startAt = Number(__ENV.START_AT_MS || 0);
if (!Number.isSafeInteger(startAt) || startAt < 0 || (role !== 'combined' && startAt === 0)) {
  throw new Error('Split roles require a positive integer START_AT_MS shared by both processes');
}
const normalRate = 2;
const hotRates = (__ENV.HOT_RATES || '20,100,200').split(',').map(Number);
if (hotRates.length !== 3 || hotRates.some((rate) => !Number.isInteger(rate) || rate < 1 || rate > 500)) {
  throw new Error('HOT_RATES must contain exactly three integer rates in 1..500');
}
// 每个观众仍受服务端连接桶约束。默认连接桶为 5/s；启动入口另核对实际配置口径。
// 给轮转发送留余量，避免把单连接限流误当成热点房间吞吐边界。
if (Math.max(...hotRates) / hotConnections > 4) throw new Error('Use more HOT_CONNECTIONS: each sender must target <=4/s');
const configuredConnections = normalRooms * normalConnections + hotConnections;
if (configuredConnections > 2000) throw new Error('Total connections exceed this script\'s 2000-connection limit');
const phases = [{ name: 'normal-only', hotRate: 0 }, ...hotRates.map((rate, i) => ({ name: `hot-${rate}-${i}`, hotRate: rate }))];
const roomDefinitions = Array.from({ length: normalRooms + 1 }, (_, index) => ({
  name: index === normalRooms ? 'hot' : `normal-${index}`,
  hot: index === normalRooms,
  connections: index === normalRooms ? hotConnections : normalConnections,
  rates: phases.map((phase) => index === normalRooms ? phase.hotRate : normalRate),
})).filter((room) => role === 'combined' || (role === 'hot' ? room.hot : !room.hot));
const totalConnections = roomDefinitions.reduce((sum, room) => sum + room.connections, 0);
const closeScheduleMs = closeBatchSize > 0 ? (Math.ceil(totalConnections / closeBatchSize) - 1) * closeBatchIntervalMs : 0;
if (diagnostic && closeScheduleMs > 10000) throw new Error('Diagnostic close batch schedule exceeds 10-second budget');
let dedupBytes = 0;
roomDefinitions.forEach((room) => {
  room.messages = room.rates.reduce((total, rate) => total + rate * seconds, 0);
  dedupBytes += room.connections * room.messages;
});
if (dedupBytes > 64 * 1024 * 1024) throw new Error('Deduplication tables exceed 64 MiB; reduce phase duration/connections');

const joined = new Counter('t4_joined');
const errors = new Counter('t4_errors');
const duplicates = new Counter('t4_duplicates');
const orderViolations = new Counter('t4_order_violations');
const isolationFailures = new Counter('t4_isolation_failures');
const unexpectedCloses = new Counter('t4_unexpected_closes');
const missingAccepted = new Counter('t4_missing_accepted');
const missingResults = new Counter('t4_missing_results');
// Gauge 保存本进程全部加入和实际开始的时刻；两份报告须同时满足就绪早于共同起点。
const readyAt = new Gauge('t4_ready_at_ms');
const startedAt = new Gauge('t4_started_at_ms');
const startLag = new Gauge('t4_start_lag_ms');

// 为每个“房间×阶段”登记独立指标。名称数量由上面的房间/阶段上限限定，不用消息ID作标签。
const statistics = roomDefinitions.map((room, r) => phases.map((phase, p) => {
  const prefix = `t4_r${r}_p${p}_`;
  return {
    prefix, sent: new Counter(`${prefix}sent`), accepted: new Counter(`${prefix}accepted`),
    rejected: new Counter(`${prefix}rejected`), roomRejected: new Counter(`${prefix}room_rejected`),
    connectionRejected: new Counter(`${prefix}connection_rejected`), otherRejected: new Counter(`${prefix}other_rejected`),
    received: new Counter(`${prefix}received`), missing: new Counter(`${prefix}missing`),
    latencySamples: new Counter(`${prefix}latency_samples`),
    rate: new Gauge(`${prefix}actual_rate`), latency: new Trend(`${prefix}latency_ms`),
    lag: new Trend(`${prefix}send_lag_ms`), broadcast: new Trend(`${prefix}broadcast_bytes`),
  };
}));
const thresholds = {
  checks: ['rate==1'], iterations: ['count==1'], t4_joined: [`count==${totalConnections}`],
  t4_errors: ['count==0'], t4_duplicates: ['count==0'], t4_order_violations: ['count==0'],
  t4_isolation_failures: ['count==0'], t4_unexpected_closes: ['count==0'],
  t4_missing_accepted: ['count==0'], t4_missing_results: ['count==0'],
};
statistics.forEach((roomStats, r) => roomStats.forEach((stat, p) => {
  const rate = roomDefinitions[r].rates[p];
  thresholds[`${stat.prefix}sent`] = [`count==${rate * seconds}`];
  if (rate > 0) {
    thresholds[`${stat.prefix}actual_rate`] = [`value>=${rate * 0.95}`, `value<=${rate * 1.05}`];
    thresholds[`${stat.prefix}send_lag_ms`] = ['max<1000'];
  }
  // 普通房间不应因自身 2/s 输入而拒绝。热点只允许明确的房间限流，其他拒绝计入 errors。
  if (!roomDefinitions[r].hot) thresholds[`${stat.prefix}rejected`] = ['count==0'];
}));
if (startAt) thresholds.t4_start_lag_ms = ['value>=0', 'value<1000'];

export const options = {
  scenarios: { hotspot: { executor: 'shared-iterations', vus: 1, iterations: 1,
    maxDuration: `${Math.max(60, Math.ceil((startAt - Date.now()) / 1000)) + phases.length * seconds + drainSeconds + 20}s`, gracefulStop: '5s' } },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'], thresholds,
};

export default function () {
  // 角色也进入房间命名，保证两个进程即使恰好生成相同时间和随机数，也不会合并成同一房间。
  const token = `${Date.now()}-${role}-${Math.floor(Math.random() * 100000)}`;
  const idPrefix = `t4-${token}:`;
  const rooms = roomDefinitions.map((definition, r) => {
    let offset = 0;
    const windows = definition.rates.map((rate) => {
      const window = { start: offset, count: rate * seconds, next: 0 };
      offset += window.count;
      return window;
    });
    return {
      ...definition, id: `t4-${token}-${r}`, windows, instance: null, text: null, sockets: [],
      sentAt: new Float64Array(definition.messages), status: new Uint8Array(definition.messages),
      receipts: new Uint16Array(definition.messages),
      seen: Array.from({ length: definition.connections }, () => new Uint8Array(definition.messages)),
      lastSequence: new Uint32Array(definition.connections), sequenceByInput: new Uint32Array(definition.messages),
      inputBySequence: new Int32Array(definition.messages + 1).fill(-1),
      counts: phases.map(() => ({ sent: 0, accepted: 0, rejected: 0, received: 0 })),
    };
  });
  const clients = [];
  rooms.forEach((room, r) => { for (let v = 0; v < room.connections; v++) clients.push({ r, v }); });
  let ready = 0;
  let opened = 0;
  let phase = -1;
  let phaseStarted = 0;
  let finished = false;
  // 默认路径仍一次结算后关闭。诊断路径先settling停止输入、保留接收统计，
  // 再closing发关闭请求，直到全部回调或有限截止才finished；这些状态仅属本VU。
  let settling = false;
  let closing = false;
  let closeRequested = 0;
  let closeCompleted = 0;
  let diagnosticStallTimer;
  let diagnosticTicker;
  let closeTicker;
  let closeDeadline;
  let firstFailure = null;
  let maxTickDriftMs = 0;
  let maxObservedSendLagMs = 0;
  let lastTickAt = 0;
  const requestedCloseSockets = new Set();
  const closedSockets = new Set();
  let runId;
  let opening;
  let ticking;
  let heartbeat;
  let deadline;
  let draining;
  let waiting;
  [joined, errors, duplicates, orderViolations, isolationFailures, unexpectedCloses, missingAccepted, missingResults].forEach((metric) => metric.add(0));
  statistics.forEach((row) => row.forEach((stat) => {
    ['sent', 'accepted', 'rejected', 'roomRejected', 'connectionRejected', 'otherRejected', 'received', 'missing', 'latencySamples'].forEach((name) => stat[name].add(0));
    stat.rate.add(0);
  }));

  function phaseOf(room, index) {
    return room.windows.findIndex((window) => index >= window.start && index < window.start + window.count);
  }

  /** 每秒一次累计样本及少量生命周期事件；条数受短时长、连接上限和关闭批数共同约束。 */
  function recordDiagnostic(type, extra = {}) {
    if (!diagnostic) return;
    console.log(`T4_DIAGNOSTIC ${JSON.stringify({
      type, atMs: Date.now(), phase, sent: rooms.reduce((sum, room) => sum + room.counts.reduce((n, count) => n + count.sent, 0), 0),
      accepted: rooms.reduce((sum, room) => sum + room.counts.reduce((n, count) => n + count.accepted, 0), 0),
      acceptedDeliveryDemand: rooms.reduce((sum, room) => sum + room.counts.reduce((n, count) => n + count.accepted * room.connections, 0), 0),
      received: rooms.reduce((sum, room) => sum + room.counts.reduce((n, count) => n + count.received, 0), 0),
      createdSockets: opened, closeRequested, closeCompleted, closedObserved: closedSockets.size,
      maxTickDriftMs, maxObservedSendLagMs, ...extra,
    })}`);
  }

  function rememberFailure(reason) {
    if (firstFailure) return;
    firstFailure = reason;
    recordDiagnostic('first_failure', { reason });
  }

  function fail(reason) {
    if (finished) return;
    rememberFailure(reason);
    errors.add(1);
    if (diagnostic) beginSettlement(reason);
    else finish(reason);
  }

  // 有些校验错误按原逻辑仅计数、不立即停止；同样保存首个原因，但不改变默认控制流。
  function countError(reason) { rememberFailure(reason); errors.add(1); }

  function closeSocket(socket) {
    if (requestedCloseSockets.has(socket)) return;
    requestedCloseSockets.add(socket);
    closeRequested++;
    if (socket.readyState === 3) {
      closedSockets.add(socket);
      // 可能在本方发关闭请求前已经收到远端close回调，不能重复增加完成数。
      closeCompleted = [...requestedCloseSockets].filter(item => closedSockets.has(item)).length;
      return;
    }
    if (socket.readyState < 2) {
      try { socket.close(); }
      catch { rememberFailure('close_send'); errors.add(1); }
    }
  }

  function closeNextBatch() {
    const sockets = rooms.flatMap((room) => room.sockets)
      .filter((socket) => !requestedCloseSockets.has(socket) && !closedSockets.has(socket));
    const batch = closeBatchSize > 0 ? sockets.slice(0, closeBatchSize) : sockets;
    batch.forEach(closeSocket);
    if (batch.length) recordDiagnostic('close_requested', { batch: batch.length });
    if (requestedCloseSockets.size >= opened && closeTicker !== undefined) {
      clearInterval(closeTicker);
      closeTicker = undefined;
    }
    if (closedSockets.size >= opened) finish('close_complete');
  }

  function beginSettlement(reason) {
    if (settling || finished) return;
    settling = true;
    [opening, ticking, heartbeat].forEach((timer) => { if (timer !== undefined) clearInterval(timer); });
    [deadline, waiting, diagnosticStallTimer].forEach((timer) => { if (timer !== undefined) clearTimeout(timer); });
    recordDiagnostic('input_stopped', { reason, firstFailure });
    draining = setTimeout(() => {
      closing = true;
      recordDiagnostic('closing_started', { createdSockets: opened });
      closeDeadline = setTimeout(() => {
        rememberFailure('close_timeout');
        errors.add(1);
        finish('close_timeout');
      }, closeScheduleMs + 5000);
      closeTicker = setInterval(closeNextBatch, closeBatchIntervalMs);
      closeNextBatch();
    }, drainSeconds * 1000);
  }

  /** 有限排空后结算；诊断模式在关闭请求后继续接收，记录关闭时序。 */
  function finish(reason = 'settled') {
    if (finished) return;
    finished = true;
    [opening, ticking, heartbeat, diagnosticTicker, closeTicker].forEach((timer) => { if (timer !== undefined) clearInterval(timer); });
    [deadline, draining, waiting, closeDeadline, diagnosticStallTimer].forEach((timer) => { if (timer !== undefined) clearTimeout(timer); });
    let absent = 0;
    let unconfirmed = 0;
    rooms.forEach((room, r) => {
      room.windows.forEach((window, p) => {
        let missing = 0;
        for (let index = window.start; index < window.start + window.count; index++) {
          if (!room.sentAt[index]) continue;
          if (room.status[index] === 0) unconfirmed++;
          if (room.status[index] === 1) missing += room.connections - room.receipts[index];
          if (room.status[index] === 2 && room.receipts[index] > 0) countError('rejected_but_received');
        }
        statistics[r][p].missing.add(missing);
        absent += missing;
      });
      if (!diagnostic) room.sockets.forEach((socket) => { if (socket.readyState === 1) socket.close(); });
    });
    missingAccepted.add(absent);
    missingResults.add(unconfirmed);
    check(null, { '各观众收齐全部已接受输入且发送结果齐全': () => absent === 0 && unconfirmed === 0 });
    if (diagnostic) {
      if (absent > 0) rememberFailure('missing_accepted');
      if (unconfirmed > 0) rememberFailure('missing_results');
      // 先完成最终计数和错误结算，再发唯一终态；终态后不再出现校验事件。
      recordDiagnostic('settled', { reason, firstFailure, closeCompleted, closeRequested, closeMode: closeBatchSize > 0 ? 'batched' : 'all' });
    }
  }

  /** 每个房间轮转发送；记录相对固定计划的延后，不用低实际输入伪装目标负载。 */
  function sendDue(room, r, now) {
    const rate = room.rates[phase];
    if (!rate) return;
    const window = room.windows[phase];
    let burst = 0;
    while (window.next < window.count && burst++ < 100) {
      const planned = phaseStarted + window.next * 1000 / rate;
      if (now < planned) break;
      const index = window.start + window.next;
      const message = { type: 'send', clientMessageId: `${idPrefix}${r}:${String(index).padStart(6, '0')}`,
        text: '', clientSentAt: now };
      // 所有请求字段为 ASCII；完整 JSON 固定 256 UTF-8 字节，广播大小另行实测。
      const padding = 256 - JSON.stringify(message).length;
      if (padding < 1 || padding > 200) { fail('message_padding'); return; }
      message.text = 'x'.repeat(padding);
      room.text = room.text || message.text;
      room.sentAt[index] = now;
      try { room.sockets[index % room.connections].send(JSON.stringify(message)); }
      catch { room.sentAt[index] = 0; fail('send_exception'); return; }
      room.counts[phase].sent++;
      statistics[r][phase].sent.add(1);
      statistics[r][phase].lag.add(Math.max(0, now - planned));
      maxObservedSendLagMs = Math.max(maxObservedSendLagMs, Math.max(0, now - planned));
      window.next++;
    }
  }

  function startPhase(p) {
    phase = p;
    // 分进程必须使用同一个绝对阶段边界；不能因前一阶段回调迟到而让两边逐渐错开。
    phaseStarted = startAt ? startAt + p * seconds * 1000 : Date.now();
    recordDiagnostic('phase_started', { name: phases[p].name });
    if (diagnostic && diagnosticStallMs > 0 && p === 1) {
      // 在热点20/s阶段开始半秒后阻塞本VU一次；其他k6进程及服务端未被暂停。
      // 注入事件单独落盘，失败只能证明门槛/归档有效，不能作为服务端性能结果。
      diagnosticStallTimer = setTimeout(() => {
        if (finished || settling) return;
        recordDiagnostic('injected_stall_started', { durationMs: diagnosticStallMs });
        sleep(diagnosticStallMs / 1000);
        recordDiagnostic('injected_stall_finished');
      }, 500);
    }
    console.log(`T4 phase ${p + 1}/${phases.length}: ${phases[p].name}; ordinary ${normalRate}/s/room; hot ${phases[p].hotRate}/s`);
  }

  function tick() {
    if (finished || settling || phase < 0) return;
    const now = Date.now();
    if (lastTickAt) maxTickDriftMs = Math.max(maxTickDriftMs, Math.max(0, now - lastTickAt - 10));
    lastTickAt = now;
    rooms.forEach((room, r) => { if (!finished && !settling) sendDue(room, r, now); });
    if (finished || settling) return;
    if (now - phaseStarted >= seconds * 1000 && rooms.every((room) => room.windows[phase].next === room.windows[phase].count)) {
      rooms.forEach((room, r) => statistics[r][phase].rate.add(room.counts[phase].sent * 1000 / (now - phaseStarted)));
      if (phase + 1 < phases.length) startPhase(phase + 1);
      else {
        clearInterval(ticking);
        console.log(`T4 input stopped; drain ${drainSeconds}s`);
        if (diagnostic) beginSettlement('input_complete');
        else draining = setTimeout(() => finish('settled'), drainSeconds * 1000);
      }
    } else if (startAt && now >= phaseStarted + seconds * 1000) {
      // 到边界仍没发完计划，说明工具无法维持负载；明确失败，不能偷偷延长本阶段。
      fail('phase_send_lag');
    }
  }

  function startTraffic() {
    const ready = Date.now();
    readyAt.add(ready);
    clearTimeout(deadline);
    if (startAt && ready >= startAt) {
      console.error(`T4 ${role}: joined too late for shared start ${startAt}`);
      fail('joined_late'); return;
    }
    deadline = setTimeout(() => fail('deadline'),
      Math.max(0, startAt - ready) + (phases.length * seconds + drainSeconds + 15) * 1000);
    heartbeat = setInterval(() => rooms.forEach((room) => room.sockets.forEach((socket) => {
      if (socket.readyState === 1) {
        try { socket.send(JSON.stringify({ type: 'ping' })); }
        catch { fail('heartbeat_send'); }
      }
    })), 15000);
    function begin() {
      if (finished || settling) return;
      // 定时器允许少量滞后，但绝不提前发；若系统时钟回拨，则继续等到约定时刻。
      if (startAt && Date.now() < startAt) { waiting = setTimeout(begin, startAt - Date.now()); return; }
      startedAt.add(Date.now());
      startLag.add(startAt ? Date.now() - startAt : 0);
      startPhase(0);
      ticking = setInterval(tick, 10);
      tick();
    }
    console.log(`T4 ${role}: all ${totalConnections} joined; shared start=${startAt || 'immediate'}`);
    waiting = setTimeout(begin, Math.max(0, startAt - ready));
  }

  /** 消息 ID 只允许本轮、本房间、实际已经发出的输入，防止串房或未知数据被算成成功。 */
  function inputIndex(room, r, id) {
    const prefix = `${idPrefix}${r}:`;
    if (typeof id !== 'string' || !id.startsWith(prefix)) return -1;
    const index = Number(id.slice(prefix.length));
    return Number.isInteger(index) && index >= 0 && index < room.messages && room.sentAt[index] > 0 ? index : -1;
  }

  function receive(room, r, viewer, data) {
    if (finished) return;
    let event;
    try { event = JSON.parse(data); } catch { fail('invalid_json'); return; }
    if (event.type === 'joined') {
      if (viewer.joined || event.roomId !== room.id) { fail('invalid_joined'); return; }
      viewer.joined = true;
      room.instance = room.instance || event.roomInstanceId;
      if (event.roomInstanceId !== room.instance) { fail('room_instance_mismatch'); return; }
      joined.add(1);
      if (++ready === totalConnections && !settling) startTraffic();
      return;
    }
    if (event.type === 'pong') return;
    const index = inputIndex(room, r, event.clientMessageId);
    if (index < 0) { countError('invalid_message_identity'); isolationFailures.add(1); return; }
    const p = phaseOf(room, index);
    const stat = statistics[r][p];
    if (event.type === 'send_result') {
      if (viewer.v !== index % room.connections) { countError('result_recipient_mismatch'); return; }
      if (room.status[index]) { countError('duplicate_send_result'); return; }
      if (event.status === 'accepted') {
        room.status[index] = 1; room.counts[p].accepted++; stat.accepted.add(1);
      } else if (event.status === 'rejected') {
        room.status[index] = 2; room.counts[p].rejected++; stat.rejected.add(1);
        if (event.reason === 'room_rate_limit' && room.hot) stat.roomRejected.add(1);
        else {
          if (event.reason === 'connection_rate_limit') stat.connectionRejected.add(1);
          else stat.otherRejected.add(1);
          countError('unexpected_rejection');
        }
      } else countError('unknown_send_result');
      return;
    }
    if (event.type !== 'danmaku') { countError('unexpected_event_type'); return; }
    runId = runId || event.runId;
    if (event.roomId !== room.id || event.roomInstanceId !== room.instance || event.runId !== runId) {
      isolationFailures.add(1); countError('broadcast_room_mismatch'); return;
    }
    if (event.clientSentAt !== room.sentAt[index] || event.text !== room.text) { countError('broadcast_payload_mismatch'); return; }
    if (room.seen[viewer.v][index]) { rememberFailure('duplicate_delivery'); duplicates.add(1); return; }
    const sequence = event.roomSeq;
    if (!Number.isInteger(sequence) || sequence < 1 || sequence > room.messages) { rememberFailure('invalid_sequence'); orderViolations.add(1); return; }
    if (sequence <= room.lastSequence[viewer.v]
      || (room.sequenceByInput[index] && room.sequenceByInput[index] !== sequence)
      || (room.inputBySequence[sequence] !== -1 && room.inputBySequence[sequence] !== index)) {
      rememberFailure('order_violation'); orderViolations.add(1);
    }
    room.sequenceByInput[index] = sequence;
    room.inputBySequence[sequence] = index;
    room.lastSequence[viewer.v] = sequence;
    room.seen[viewer.v][index] = 1;
    room.receipts[index]++;
    room.counts[p].received++;
    stat.received.add(1);
    if (viewer.v % sampleEvery === 0) {
      const elapsed = Date.now() - room.sentAt[index];
      if (elapsed < 0) countError('negative_latency_clock');
      stat.latency.add(elapsed);
      stat.latencySamples.add(1);
    }
    if (viewer.v === 0) stat.broadcast.add(encodeURIComponent(data).replace(/%[A-F\d]{2}/gi, 'x').length);
  }

  function openBatch() {
    const end = Math.min(opened + 20, clients.length);
    while (!finished && !settling && opened < end) {
      const viewer = clients[opened];
      const room = rooms[viewer.r];
      let socket;
      try {
        socket = new WebSocket(__ENV.WS_URL || 'ws://127.0.0.1:8082/ws');
      } catch { fail('socket_create'); break; }
      opened++;
      room.sockets.push(socket);
      socket.addEventListener('open', () => {
        if (finished || settling) { closeSocket(socket); return; }
        try {
          socket.send(JSON.stringify({ type: 'join', roomId: room.id, nickname: `r${viewer.r}-v${viewer.v}` }));
        } catch { fail('join_send'); }
      });
      socket.addEventListener('message', ({ data }) => receive(room, viewer.r, viewer, data));
      socket.addEventListener('error', () => fail('socket_error'));
      socket.addEventListener('close', (event = {}) => {
        // 有限截止后的回调不回填诊断终态，也不在settled之后追加事件；
        // 这里停止的是统计，传输层是否已彻底退出仍由k6进程和服务端清理指标分别证明。
        if (finished) return;
        if (closedSockets.has(socket)) return;
        closedSockets.add(socket);
        if (closedSockets.size === 1) recordDiagnostic('first_socket_closed', {
          code: event.code ?? null, reason: String(event.reason ?? '').slice(0, 120),
          requestedByClient: requestedCloseSockets.has(socket),
        });
        if (requestedCloseSockets.has(socket)) {
          closeCompleted++;
          if (closeCompleted === 1) recordDiagnostic('first_close_completed');
          if (closing && closedSockets.size === opened) finish('close_complete');
        } else if (!finished && !settling) {
          unexpectedCloses.add(1);
          fail('unexpected_close');
        }
      });
    }
    if (opened === clients.length || finished) clearInterval(opening);
  }
  deadline = setTimeout(() => fail('join_deadline'), 60000);
  if (diagnostic) {
    recordDiagnostic('started', { role, totalConnections, closeBatchSize, closeBatchIntervalMs, drainSeconds });
    diagnosticTicker = setInterval(() => recordDiagnostic('sample'), 1000);
  }
  opening = setInterval(openBatch, 100);
  openBatch();
}

/** 汇总只读取 k6 指标；各房间/阶段独立输出，不平均合并不同人群的 P99。 */
export function handleSummary(data) {
  const value = (name, field = 'count') => data.metrics[name]?.values?.[field] ?? 0;
  const report = {
    scenario: 'T4-initial', role, totalConnections, configuredConnections, normalRooms, normalConnections, hotConnections,
    plannedStartAtMs: startAt, readyAtMs: value('t4_ready_at_ms', 'value'),
    startedAtMs: value('t4_started_at_ms', 'value'), startLagMs: value('t4_start_lag_ms', 'value'),
    phaseSeconds: seconds, hotRates, requestBytes: 256, latencySampleEvery: sampleEvery, dedupBytes,
    joined: value('t4_joined'), errors: value('t4_errors'), duplicates: value('t4_duplicates'),
    orderViolations: value('t4_order_violations'), isolationFailures: value('t4_isolation_failures'),
    unexpectedCloses: value('t4_unexpected_closes'), missingAccepted: value('t4_missing_accepted'),
    missingResults: value('t4_missing_results'),
    rooms: roomDefinitions.map((room, r) => ({ name: room.name, connections: room.connections,
      phases: phases.map((phase, p) => {
        const prefix = statistics[r][p].prefix;
        const sent = value(`${prefix}sent`);
        const accepted = value(`${prefix}accepted`);
        return {
          name: phase.name, targetInputPerSecond: room.rates[p], plannedInputMessages: room.rates[p] * seconds, sent, accepted,
          rejected: value(`${prefix}rejected`), roomRateRejected: value(`${prefix}room_rejected`),
          rejectionReasons: { room_rate_limit: value(`${prefix}room_rejected`), connection_rate_limit: value(`${prefix}connection_rejected`), other: value(`${prefix}other_rejected`) },
          actualInputPerSecond: value(`${prefix}actual_rate`, 'value'),
          allInputDeliveryDemand: sent * room.connections, acceptedDeliveryDemand: accepted * room.connections,
          received: value(`${prefix}received`), missingAccepted: value(`${prefix}missing`),
          latencySamples: value(`${prefix}latency_samples`), latencyMs: data.metrics[`${prefix}latency_ms`]?.values || null,
          maxSendLagMs: value(`${prefix}send_lag_ms`, 'max'),
          broadcastBytes: data.metrics[`${prefix}broadcast_bytes`]?.values || null,
        };
      }) })),
    // 脚本不决定服务端调度方式；direct/fair以runner的配置和启动命令为准，不能写死为“未实现”。
    note: 'Same-host, all readers normal; one VU per process handles only its ROLE. Split roles share absolute phase boundaries. Finite drain, sampled latency; processes still share host CPU/RAM. Server dispatch mode is recorded by the runner; this tool does not change it. Not formal 15/30-minute validation.',
  };
  if (diagnostic) {
    report.diagnostic = { enabled: true, formalEligible: false, drainSeconds, closeBatchSize, closeBatchIntervalMs,
      stallMs: diagnosticStallMs, closeTimeoutMs: closeScheduleMs + 5000,
      note: 'Observed delivery demand minus receipts is not exact server backlog.' };
  }
  const lines = report.rooms.map((room) => `${room.name}: ` + room.phases.map((p) =>
    `${p.name} input=${p.actualInputPerSecond.toFixed(2)}/s accepted=${p.accepted} rejected=${p.rejected} received=${p.received}/${p.acceptedDeliveryDemand} p99=${p.latencyMs?.['p(99)'] ?? 'n/a'}ms`).join(' | '));
  return {
    [__ENV.SUMMARY_PATH || 'load-results/hotspot-summary.json']: JSON.stringify({ report, metrics: data }, null, 2),
    stdout: `T4 joined=${report.joined}/${totalConnections}; errors=${report.errors}, missingAccepted=${report.missingAccepted}, isolation=${report.isolationFailures}\n${lines.join('\n')}\n`,
  };
}
