import { WebSocket } from 'k6/websockets';
import { Counter, Trend } from 'k6/metrics';
import { check } from 'k6';

// T0 冒烟脚本：一个虚拟用户（VU）协调 10 个同房间观众，以及 1 个独立房间的隔离探针。
// 等全部连接确认加入后再发消息，避免把“尚未加入”误算成消息丢失。
// 该脚本验证协议和统计工具，不代表已验证千连接容量。
const viewers = 10;
// 当前 k6 运行时没有可用的 WebSocket.OPEN 静态常量；数值 1 表示连接已打开。
const OPEN = 1;
// 命令行 -e MESSAGES=3 可改成短轮；上限 60 也限制了下面去重集合的内存占用。
const messages = Number(__ENV.MESSAGES || 60);
if (!Number.isInteger(messages) || messages < 1 || messages > 60) throw new Error('MESSAGES must be 1..60');
const joined = new Counter('lab_joined');
// Counter 是事件总数；accepted 数输入条数，received 数所有观众的唯一接收次数。
const accepted = new Counter('lab_accepted');
const received = new Counter('lab_received');
const duplicates = new Counter('lab_duplicates');
const errors = new Counter('lab_errors');
const isolationFailures = new Counter('lab_isolation_failures');
const orderViolations = new Counter('lab_order_violations');
// Trend 记录多次延迟样本的分布；这里添加的单位为毫秒。
const latency = new Trend('lab_e2e_ms', true);

export const options = {
  // 仅执行一轮。60 条、每秒一条，加排空时间后应能在 90 秒内结束。
  scenarios: { smoke: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '90s' } },
  thresholds: {
    // 除总体 10×消息数外，结束时还会逐观众校验，避免某人缺失被别人的接收量掩盖。
    checks: ['rate==1'], lab_joined: ['count==11'], lab_accepted: [`count==${messages}`],
    lab_received: [`count==${viewers * messages}`], lab_duplicates: ['count==0'],
    lab_errors: ['count==0'], lab_isolation_failures: ['count==0'],
    lab_order_violations: ['count==0'], iterations: ['count==1'],
  },
};

export default function () {
  const sockets = [];
  const counts = Array(viewers).fill(0);
  // 每个观众各有去重集合和上一个序号，分别检测重复、唯一接收和乱序。
  const seen = Array.from({ length: viewers }, () => new Set());
  const lastSequence = Array(viewers).fill(0);
  // 每轮房间名加入时间戳，避免其他演示页面在 lobby 发消息污染本轮统计。
  const room = `smoke-${Date.now()}`;
  let ready = 0;
  let sent = 0;
  let finished = false;
  let interval;
  let heartbeat;
  let drain;
  // 主动写入零样本，让没有发生的错误也能出现在摘要和阈值判断中。
  for (const metric of [joined, accepted, received, duplicates, errors, isolationFailures, orderViolations]) metric.add(0);

  // 成功排空、错误或超时都进入这个收尾函数；finished 让它可重复触发而只执行一次。
  function finish() {
    if (finished) return;
    finished = true;
    // 停止全部定时任务，再检查统计并关闭连接，保证虚拟用户能够正常结束迭代。
    clearTimeout(deadline);
    clearTimeout(drain);
    clearInterval(interval);
    clearInterval(heartbeat);
    check(ready, { 'all viewers and isolation probe joined': (n) => n === 11 });
    counts.forEach((count) => check(count, { 'every viewer received every unique message': (n) => n === messages }));
    sockets.forEach((socket) => { if (socket.readyState === OPEN) socket.close(); });
  }

  const deadline = setTimeout(() => { errors.add(1); finish(); }, 80000);
  // 只由第一个同房间观众发送，其余连接负责接收，发送者自己也会收到广播。
  function sendOne() {
    sockets[0].send(JSON.stringify({ type: 'send', clientMessageId: `smoke-${sent}`,
      text: 'smoke message', clientSentAt: Date.now() }));
    if (++sent === messages) {
      clearInterval(interval);
      // 最后一条发出后留 1.5 秒排空，避免遗漏还在网络或事件回调中的尾部消息。
      drain = setTimeout(finish, 1500);
    }
  }
  for (let i = 0; i <= viewers; i++) {
    // i=0..9 加同一房间，i=10 加隔离房间，合计 11 条连接。
    const socket = new WebSocket(__ENV.WS_URL || 'ws://127.0.0.1:8080/ws');
    sockets.push(socket);
    socket.addEventListener('open', () => {
      if (finished) { socket.close(); return; }
      socket.send(JSON.stringify({ type: 'join',
        roomId: i === viewers ? `${room}-isolated` : room, nickname: `smoke-${i}` }));
    });
    socket.addEventListener('message', ({ data }) => {
      const event = JSON.parse(data);
      if (event.type === 'joined') {
        joined.add(1);
        if (++ready === viewers + 1) {
          // 所有加入确认到齐才通过“就绪屏障”；长轮需要心跳避免观众空闲断开。
          heartbeat = setInterval(() => sockets.forEach((ws) => {
            if (ws.readyState === OPEN) ws.send(JSON.stringify({ type: 'ping' }));
          }), 15000);
          // 先保存 interval 再发首条，MESSAGES=1 时 sendOne 才能正确取消这个定时器。
          interval = setInterval(sendOne, 1000);
          sendOne();
        }
      } else if (event.type === 'danmaku') {
        // 独立房间收到弹幕或普通观众收到错误房间消息，都属于串房失败。
        if (i === viewers || event.roomId !== room) { isolationFailures.add(1); return; }
        const index = Number(event.clientMessageId.replace('smoke-', ''));
        if (!Number.isInteger(index) || index < 0 || index >= messages) { errors.add(1); return; }
        if (seen[i].has(index)) { duplicates.add(1); return; }
        // 序号必须递增；唯一接收只统计第一次到达的已知消息，不用重复消息填补缺失。
        if (!Number.isInteger(event.roomSeq) || event.roomSeq <= lastSequence[i]) orderViolations.add(1);
        lastSequence[i] = event.roomSeq;
        seen[i].add(index);
        counts[i]++;
        received.add(1);
        // 所有客户端都在同一个压测进程，用同一台机器的时间，避免跨主机时钟误差。
        const elapsed = Date.now() - event.clientSentAt;
        if (elapsed < 0) errors.add(1);
        latency.add(elapsed);
      } else if (event.type === 'send_result' && event.status === 'accepted') {
        accepted.add(1);
      } else if (event.type !== 'pong') {
        // 包括发送被拒绝在内的其他响应，都是本轮不预期的情况，不能静默忽略。
        errors.add(1);
      }
    });
    socket.addEventListener('error', () => { errors.add(1); finish(); });
    // finish 主动关闭是正常结束；尚未结束就断开必须计错，不能剔除该观众。
    socket.addEventListener('close', () => { if (!finished) { errors.add(1); finish(); } });
  }
}

// k6 结束时写完整 JSON 并输出易读计数；阈值失败由 k6 返回非零状态码。
export function handleSummary(data) {
  const path = __ENV.SUMMARY_PATH || 'load-results/smoke-summary.json';
  const count = (name) => data.metrics[name]?.values?.count ?? 0;
  return {
    [path]: JSON.stringify(data, null, 2),
    stdout: `Smoke: joined=${count('lab_joined')}, accepted=${count('lab_accepted')}, received=${count('lab_received')}/${viewers * messages}, errors=${count('lab_errors')}, duplicates=${count('lab_duplicates')}, isolationFailures=${count('lab_isolation_failures')}\nSummary: ${path}\n`,
  };
}
