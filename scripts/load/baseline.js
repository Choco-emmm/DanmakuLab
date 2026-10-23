import { WebSocket } from 'k6/websockets';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { check } from 'k6';

/**
 * T1 连接规模基线：一个 VU 协调一个固定房间的全部连接。
 * 与 smoke.js 一样，连接数由脚本创建的 WebSocket 数量决定，不能通过 -u 修改。
 * 所有客户端 joined 后，先预热，再测量，最后停止输入并等待有限排空。
 * 默认每秒 5 条输入；它用于比较连接规模，不代表 20 条/秒或慢消费者验收。
 */
function integer(name, fallback, min, max) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer in ${min}..${max}`);
  }
  return value;
}

const connections = integer('CONNECTIONS', 100, 1, 2000);
const warmupSeconds = integer('WARMUP_SECONDS', 60, 0, 300);
const measureSeconds = integer('DURATION_SECONDS', 180, 1, 600);
const rate = integer('RATE', 5, 1, 20);
const drainSeconds = integer('DRAIN_SECONDS', 5, 1, 30);
const sampleEvery = integer('LATENCY_SAMPLE_EVERY', 10, 1, 2000);
const warmupMessages = warmupSeconds * rate;
const measureMessages = measureSeconds * rate;
const totalMessages = warmupMessages + measureMessages;
const periodMs = 1000 / rate;
const OPEN = 1;
const requestBytes = 256;
// 每条连接、每个消息编号只占一个字节，限制去重表总预算为 32 MiB。
// 例如 1000 连接 × 1200 条预热及测量消息约 1.2 MB；不会无限保存历史。
if (connections * totalMessages > 32 * 1024 * 1024) throw new Error('Deduplication budget exceeds 32 MiB');

const joined = new Counter('t1_joined');
const sent = new Counter('t1_sent');
const accepted = new Counter('t1_accepted');
const received = new Counter('t1_received');
const warmupReceived = new Counter('t1_warmup_received');
const missing = new Counter('t1_missing');
const duplicates = new Counter('t1_duplicates');
const errors = new Counter('t1_errors');
const orderViolations = new Counter('t1_order_violations');
const unexpectedCloses = new Counter('t1_unexpected_closes');
const latencySamples = new Counter('t1_latency_samples');
// 接收完整率统计所有连接；延迟固定抽样编号 0、10、20……的连接，减小工具开销。
const latency = new Trend('t1_e2e_ms', true);
const outboundBytes = new Trend('t1_broadcast_bytes');
const sendLag = new Trend('t1_send_lag_ms', true);
const actualRate = new Gauge('t1_actual_input_per_second');
const actualDuration = new Gauge('t1_measure_seconds');
const joinDuration = new Gauge('t1_join_seconds');

export const options = {
  scenarios: { baseline: {
    executor: 'shared-iterations', vus: 1, iterations: 1,
    maxDuration: `${60 + warmupSeconds + measureSeconds + drainSeconds + 30}s`,
    gracefulStop: '5s',
  } },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate==1'], iterations: ['count==1'],
    t1_joined: [`count==${connections}`],
    t1_sent: [`count==${measureMessages}`],
    t1_accepted: [`count==${totalMessages}`],
    t1_received: [`count==${connections * measureMessages}`],
    t1_warmup_received: [`count==${connections * warmupMessages}`],
    t1_missing: ['count==0'], t1_duplicates: ['count==0'], t1_errors: ['count==0'],
    t1_order_violations: ['count==0'], t1_unexpected_closes: ['count==0'],
    // 工具若跟不上发送计划，也要使本轮失败；不能把降速后的结果当成目标负载。
    // 这只是输入有效性标准；T1 不预设延迟 SLO，以便保留慢或失败的真实基线。
    t1_actual_input_per_second: [`value>=${rate * 0.95}`, `value<=${rate * 1.05}`],
  },
};

export default function () {
  const sockets = [];
  const seen = Array.from({ length: connections }, () => new Uint8Array(totalMessages));
  const measuredCounts = Array(connections).fill(0);
  const warmupCounts = Array(connections).fill(0);
  const lastSequence = Array(connections).fill(0);
  const joinedFlags = Array(connections).fill(false);
  const acceptedFlags = new Uint8Array(totalMessages);
  // 不同连接的请求可能以不同顺序取得房间锁。客户端编号用于去重，不能代替服务端顺序。
  // 首次观测建立“消息编号 ↔ 房间序号”映射，其余观众必须一致，每个序号只能属于一条消息。
  const sequenceByMessage = Array(totalMessages).fill(0);
  const messageBySequence = Array(totalMessages + 1).fill(-1);
  const sentAt = Array(totalMessages).fill(0);
  const texts = Array(totalMessages);
  const room = `t1-${Date.now()}`;
  const prefix = `${room}-`;
  const openingStarted = Date.now();
  let ready = 0;
  let next = 0;
  let finished = false;
  let measureStarted = 0;
  let measureEnded = 0;
  let roomInstanceId;
  let runId;
  let opening;
  let sending;
  let heartbeat;
  let draining;
  let deadline;
  for (const metric of [joined, sent, accepted, received, warmupReceived, missing,
    duplicates, errors, orderViolations, unexpectedCloses, latencySamples]) metric.add(0);
  actualRate.add(0);
  actualDuration.add(0);

  /**
   * 所有退出路径共用幂等收尾：即使错误和超时相继发生，也只结算一次。
   * 断开的观众仍留在分母中；缺失不能通过删除观众来消除。
   */
  function finish() {
    if (finished) return;
    finished = true;
    clearInterval(opening);
    clearInterval(sending);
    clearInterval(heartbeat);
    clearTimeout(draining);
    clearTimeout(deadline);
    check(ready, { 'all configured connections joined': (n) => n === connections });
    let absent = 0;
    for (let i = 0; i < connections; i++) {
      check(measuredCounts[i], { 'each viewer received all measured messages': (n) => n === measureMessages });
      check(warmupCounts[i], { 'each viewer received all warmup messages': (n) => n === warmupMessages });
      absent += measureMessages - measuredCounts[i];
    }
    missing.add(absent);
    // 提前失败时默认的 0 速率使本轮不通过，不伪造完整测量窗口。
    if (measureStarted && measureEnded) {
      const seconds = (measureEnded - measureStarted) / 1000;
      actualDuration.add(seconds);
      actualRate.add((next - warmupMessages) / seconds);
    }
    sockets.forEach((socket) => { if (socket.readyState === OPEN) socket.close(); });
    console.log(`T1 finished: joined=${ready}/${connections}, measured unique=${measuredCounts.reduce((a, b) => a + b, 0)}/${connections * measureMessages}`);
  }

  /** 固定间隔发送，不追赶积压的定时任务；记录实际速率和相对计划的延迟。 */
  function sendOne() {
    if (finished) return;
    if (next === totalMessages) {
      measureEnded = Date.now();
      clearInterval(sending);
      // 从输入窗口结束时开始等 5 秒；这不是无限等待或可靠补发。
      draining = setTimeout(finish, drainSeconds * 1000);
      console.log('T1 input stopped; draining pending deliveries');
      return;
    }
    const now = Date.now();
    if (next === warmupMessages) {
      measureStarted = now;
      console.log(`T1 measuring: ${connections} connections, target ${rate}/s for ${measureSeconds}s`);
    }
    const message = { type: 'send', clientMessageId: `${prefix}${String(next).padStart(6, '0')}`,
      text: '', clientSentAt: now };
    // 本请求的字段和值全部是 ASCII，因此字符串长度等于 UTF-8 字节数。
    // 256 字节指完整输入 JSON；服务器增加元数据后的广播 JSON 会更大，另行实测。
    message.text = 'x'.repeat(requestBytes - JSON.stringify(message).length);
    if (message.text.length > 200) { errors.add(1); finish(); return; }
    sentAt[next] = now;
    texts[next] = message.text;
    // 轮流使用已加入的连接发送，避免总输入先撞上某一个连接的令牌桶。
    // 大家都在同一房间，发送者自身也包含在观众数中。
    try {
      sockets[next % connections].send(JSON.stringify(message));
    } catch {
      errors.add(1); finish(); return;
    }
    if (next >= warmupMessages) {
      sent.add(1);
      sendLag.add(Math.max(0, now - measureStarted - (next - warmupMessages) * periodMs));
    }
    next++;
  }

  /** 收齐 joined 后启动一个发送时钟；所有回调在本 VU 的事件循环中推进。 */
  function startTraffic() {
    joinDuration.add((Date.now() - openingStarted) / 1000);
    clearTimeout(deadline);
    deadline = setTimeout(() => { errors.add(1); finish(); },
      (warmupSeconds + measureSeconds + drainSeconds + 20) * 1000);
    heartbeat = setInterval(() => sockets.forEach((socket) => {
      if (socket.readyState === OPEN) socket.send(JSON.stringify({ type: 'ping' }));
    }), 15000);
    console.log(`T1 joined all ${connections}; warmup ${warmupSeconds}s`);
    sending = setInterval(sendOne, periodMs);
    sendOne();
  }

  /** 验证已知消息编号，拒绝其他轮次、尚未发送或超出范围的消息。 */
  function messageIndex(id) {
    if (typeof id !== 'string' || !id.startsWith(prefix)) return -1;
    const index = Number(id.slice(prefix.length));
    return Number.isInteger(index) && index >= 0 && index < totalMessages && sentAt[index] > 0 ? index : -1;
  }

  function openOne(i) {
    const socket = new WebSocket(__ENV.WS_URL || 'ws://127.0.0.1:8080/ws');
    sockets.push(socket);
    socket.addEventListener('open', () => {
      if (finished) { socket.close(); return; }
      socket.send(JSON.stringify({ type: 'join', roomId: room, nickname: `viewer-${i}` }));
    });
    socket.addEventListener('message', ({ data }) => {
      if (finished) return;
      let event;
      try { event = JSON.parse(data); } catch { errors.add(1); finish(); return; }
      if (event.type === 'joined') {
        if (joinedFlags[i] || event.roomId !== room) { errors.add(1); finish(); return; }
        joinedFlags[i] = true;
        roomInstanceId = roomInstanceId || event.roomInstanceId;
        if (event.roomInstanceId !== roomInstanceId) { errors.add(1); finish(); return; }
        joined.add(1);
        if (++ready === connections) startTraffic();
      } else if (event.type === 'danmaku') {
        const index = messageIndex(event.clientMessageId);
        runId = runId || event.runId;
        if (index < 0 || event.roomId !== room || event.roomInstanceId !== roomInstanceId || event.runId !== runId
          || event.clientSentAt !== sentAt[index] || event.text !== texts[index]) {
          errors.add(1); return;
        }
        if (seen[i][index]) { duplicates.add(1); return; }
        // 只要求服务端序号递增、合法且映射一致，不要求编号 0 一定取得服务端序号 1。
        // 例如消息编号 1 先取得房间锁，所有观众一致收到它的序号 1，仍属于合法排序。
        const sequence = event.roomSeq;
        if (!Number.isInteger(sequence) || sequence < 1 || sequence > totalMessages) {
          orderViolations.add(1); return;
        }
        if (sequence <= lastSequence[i]
          || (sequenceByMessage[index] !== 0 && sequenceByMessage[index] !== sequence)
          || (messageBySequence[sequence] !== -1 && messageBySequence[sequence] !== index)) orderViolations.add(1);
        sequenceByMessage[index] = sequence;
        messageBySequence[sequence] = index;
        lastSequence[i] = event.roomSeq;
        seen[i][index] = 1;
        if (index < warmupMessages) {
          warmupCounts[i]++;
          warmupReceived.add(1);
        } else {
          measuredCounts[i]++;
          received.add(1);
          if (i % sampleEvery === 0) {
            const elapsed = Date.now() - sentAt[index];
            if (elapsed < 0) errors.add(1);
            latency.add(elapsed);
            latencySamples.add(1);
          }
          // 每条消息只让观众 0 记录一次完整广播 JSON 的 UTF-8 长度。
          if (i === 0) outboundBytes.add(encodeURIComponent(data).replace(/%[A-F\d]{2}/gi, 'x').length);
        }
      } else if (event.type === 'send_result' && event.status === 'accepted') {
        const index = messageIndex(event.clientMessageId);
        if (index < 0 || acceptedFlags[index]) { errors.add(1); return; }
        acceptedFlags[index] = 1;
        accepted.add(1);
      } else if (event.type !== 'pong') {
        // 限流拒绝等响应必须计错并保留失败结果，不能假装达到输入负载。
        errors.add(1);
      }
    });
    socket.addEventListener('error', () => { if (!finished) { errors.add(1); finish(); } });
    socket.addEventListener('close', () => {
      if (!finished) { unexpectedCloses.add(1); errors.add(1); finish(); }
    });
  }

  // 每 100 毫秒建立至多 20 条，避免把连接规模实验混成瞬间重连风暴实验。
  deadline = setTimeout(() => { errors.add(1); finish(); }, 60000);
  function openBatch() {
    const end = Math.min(sockets.length + 20, connections);
    while (!finished && sockets.length < end) openOne(sockets.length);
    if (sockets.length === connections || finished) clearInterval(opening);
  }
  opening = setInterval(openBatch, 100);
  openBatch();
}

/** handleSummary 在汇总上下文运行，读取 k6 指标，不依赖 VU 内的局部变量。 */
export function handleSummary(data) {
  const value = (name, field = 'count') => data.metrics[name]?.values?.[field] ?? 0;
  const report = {
    scenario: 'T1', connections, warmupSeconds, measureSeconds, targetInputPerSecond: rate,
    requestBytes, latencySampleEvery: sampleEvery, drainSeconds,
    measuredMessages: measureMessages, expectedMeasuredDeliveries: connections * measureMessages,
    joined: value('t1_joined'), sent: value('t1_sent'), acceptedIncludingWarmup: value('t1_accepted'),
    received: value('t1_received'), missing: value('t1_missing'),
    completeness: value('t1_received') / (connections * measureMessages),
    duplicates: value('t1_duplicates'), errors: value('t1_errors'),
    orderViolations: value('t1_order_violations'), unexpectedCloses: value('t1_unexpected_closes'),
    actualInputPerSecond: value('t1_actual_input_per_second', 'value'),
    actualMeasureSeconds: value('t1_measure_seconds', 'value'),
    joinSeconds: value('t1_join_seconds', 'value'),
    latencySamples: value('t1_latency_samples'), latencyMs: data.metrics.t1_e2e_ms?.values,
    broadcastBytes: data.metrics.t1_broadcast_bytes?.values,
    maxSendLagMs: value('t1_send_lag_ms', 'max'),
    note: 'One k6 VU coordinates all sockets. Latency is sampled; completeness covers every connection. Input JSON is 256 bytes; broadcast size is measured separately.',
  };
  return {
    [__ENV.SUMMARY_PATH || 'load-results/baseline-summary.json']: JSON.stringify({ report, metrics: data }, null, 2),
    stdout: `T1: connections=${report.joined}/${connections}, received=${report.received}/${report.expectedMeasuredDeliveries}, missing=${report.missing}, errors=${report.errors}, duplicates=${report.duplicates}, orderViolations=${report.orderViolations}, actualInput=${report.actualInputPerSecond.toFixed(3)}/s\nLatency (sampled ms): ${JSON.stringify(report.latencyMs)}\n`,
  };
}
