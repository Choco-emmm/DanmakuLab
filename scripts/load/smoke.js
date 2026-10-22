import { WebSocket } from 'k6/websockets';
import { Counter, Trend } from 'k6/metrics';
import { check } from 'k6';

// One VU coordinates a readiness barrier across ten viewers plus an isolation probe.
// This is a protocol/counting smoke test, not a capacity benchmark.
const viewers = 10;
// k6 provides numeric readyState; constructor static constants are not available in this runtime.
const OPEN = 1;
const messages = Number(__ENV.MESSAGES || 60);
if (!Number.isInteger(messages) || messages < 1 || messages > 60) throw new Error('MESSAGES must be 1..60');
const joined = new Counter('lab_joined');
const accepted = new Counter('lab_accepted');
const received = new Counter('lab_received');
const duplicates = new Counter('lab_duplicates');
const errors = new Counter('lab_errors');
const isolationFailures = new Counter('lab_isolation_failures');
const orderViolations = new Counter('lab_order_violations');
const latency = new Trend('lab_e2e_ms', true);

export const options = {
  scenarios: { smoke: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '90s' } },
  thresholds: {
    checks: ['rate==1'], lab_joined: ['count==11'], lab_accepted: [`count==${messages}`],
    lab_received: [`count==${viewers * messages}`], lab_duplicates: ['count==0'],
    lab_errors: ['count==0'], lab_isolation_failures: ['count==0'],
    lab_order_violations: ['count==0'], iterations: ['count==1'],
  },
};

export default function () {
  const sockets = [];
  const counts = Array(viewers).fill(0);
  const seen = Array.from({ length: viewers }, () => new Set());
  const lastSequence = Array(viewers).fill(0);
  const room = `smoke-${Date.now()}`;
  let ready = 0;
  let sent = 0;
  let finished = false;
  let interval;
  let heartbeat;
  let drain;
  for (const metric of [joined, accepted, received, duplicates, errors, isolationFailures, orderViolations]) metric.add(0);

  function finish() {
    if (finished) return;
    finished = true;
    clearTimeout(deadline);
    clearTimeout(drain);
    clearInterval(interval);
    clearInterval(heartbeat);
    check(ready, { 'all viewers and isolation probe joined': (n) => n === 11 });
    counts.forEach((count) => check(count, { 'every viewer received every unique message': (n) => n === messages }));
    sockets.forEach((socket) => { if (socket.readyState === OPEN) socket.close(); });
  }

  const deadline = setTimeout(() => { errors.add(1); finish(); }, 80000);
  function sendOne() {
    sockets[0].send(JSON.stringify({ type: 'send', clientMessageId: `smoke-${sent}`,
      text: 'smoke message', clientSentAt: Date.now() }));
    if (++sent === messages) {
      clearInterval(interval);
      drain = setTimeout(finish, 1500);
    }
  }
  for (let i = 0; i <= viewers; i++) {
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
          heartbeat = setInterval(() => sockets.forEach((ws) => {
            if (ws.readyState === OPEN) ws.send(JSON.stringify({ type: 'ping' }));
          }), 15000);
          // Set interval before first send: MESSAGES=1 must also clear it.
          interval = setInterval(sendOne, 1000);
          sendOne();
        }
      } else if (event.type === 'danmaku') {
        if (i === viewers || event.roomId !== room) { isolationFailures.add(1); return; }
        const index = Number(event.clientMessageId.replace('smoke-', ''));
        if (!Number.isInteger(index) || index < 0 || index >= messages) { errors.add(1); return; }
        if (seen[i].has(index)) { duplicates.add(1); return; }
        if (!Number.isInteger(event.roomSeq) || event.roomSeq <= lastSequence[i]) orderViolations.add(1);
        lastSequence[i] = event.roomSeq;
        seen[i].add(index);
        counts[i]++;
        received.add(1);
        const elapsed = Date.now() - event.clientSentAt;
        if (elapsed < 0) errors.add(1);
        latency.add(elapsed);
      } else if (event.type === 'send_result' && event.status === 'accepted') {
        accepted.add(1);
      } else if (event.type !== 'pong') {
        errors.add(1);
      }
    });
    socket.addEventListener('error', () => { errors.add(1); finish(); });
    socket.addEventListener('close', () => { if (!finished) { errors.add(1); finish(); } });
  }
}

export function handleSummary(data) {
  const path = __ENV.SUMMARY_PATH || 'load-results/smoke-summary.json';
  const count = (name) => data.metrics[name]?.values?.count ?? 0;
  return {
    [path]: JSON.stringify(data, null, 2),
    stdout: `Smoke: joined=${count('lab_joined')}, accepted=${count('lab_accepted')}, received=${count('lab_received')}/${viewers * messages}, errors=${count('lab_errors')}, duplicates=${count('lab_duplicates')}, isolationFailures=${count('lab_isolation_failures')}\nSummary: ${path}\n`,
  };
}
