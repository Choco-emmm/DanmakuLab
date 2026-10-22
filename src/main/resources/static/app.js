'use strict';
const $ = (id) => document.getElementById(id);
let socket;
let desiredRoom;
let activeRoom;
let stopped = true;
let joined = false;
let reconnectAttempt = 0;
let reconnectTimer;
let heartbeat;
let received = 0;
let lastPong = 0;

const reasons = {
  connection_rate_limit: '发送太快，请稍后重试。',
  room_rate_limit: '房间消息过多，请稍后重试。',
  room_limit: '房间数量已到上限。',
  not_joined: '请先加入房间。',
  invalid_text: '弹幕不能为空，且最多 200 个字符。',
  frame_too_large: '完整消息超过 1 KiB。',
};

function status(label, connected = false) {
  $('status').dataset.connected = String(connected);
  $('status').querySelector('span').textContent = label;
}

function enableSending(enabled) {
  joined = enabled;
  $('text').disabled = !enabled;
  $('send-button').disabled = !enabled;
}

function resetMessages() {
  $('messages').replaceChildren();
  received = 0;
  $('received').textContent = '0 条已接收';
}

function appendMessage(event) {
  const row = document.createElement('article');
  row.className = 'message';
  const meta = document.createElement('div');
  meta.className = 'message-meta';
  const nickname = document.createElement('strong');
  nickname.textContent = event.nickname;
  const sequence = document.createElement('span');
  sequence.textContent = `#${event.roomSeq}`;
  const body = document.createElement('p');
  body.textContent = event.text;
  meta.append(nickname, sequence);
  row.append(meta, body);
  $('empty')?.remove();
  $('messages').append(row);
  // Demo rendering has its own bound; it does not simulate a TCP slow consumer.
  while ($('messages').children.length > 200) $('messages').firstElementChild.remove();
  $('messages').scrollTop = $('messages').scrollHeight;
  $('received').textContent = `${++received} 条已接收`;
}

function connect() {
  clearTimeout(reconnectTimer);
  clearInterval(heartbeat);
  enableSending(false);
  status(reconnectAttempt ? '正在重连…' : '正在连接…');
  $('join-button').disabled = true;
  $('disconnect').disabled = false;
  const current = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`);
  socket = current;
  current.addEventListener('open', () => {
    if (current !== socket || stopped) { current.close(); return; }
    current.send(JSON.stringify({ type: 'join', ...desiredRoom }));
    lastPong = Date.now();
    heartbeat = setInterval(() => {
      if (current.readyState !== WebSocket.OPEN) return;
      if (Date.now() - lastPong > 45000) { current.close(); return; }
      current.send(JSON.stringify({ type: 'ping' }));
    }, 15000);
  });
  current.addEventListener('message', ({ data }) => {
    if (current !== socket) return;
    const event = JSON.parse(data);
    if (event.type === 'joined') {
      activeRoom = { ...desiredRoom };
      reconnectAttempt = 0;
      status(`已连接 / ${event.roomId}`, true);
      $('messages-title').textContent = `房间 / ${event.roomId}`;
      $('join-button').disabled = false;
      $('join-button').textContent = '切换房间';
      $('feedback').textContent = '已加入房间。断线期间的弹幕不会补发。';
      enableSending(true);
    } else if (event.type === 'danmaku' && event.roomId === activeRoom?.roomId) {
      appendMessage(event);
    } else if (event.type === 'send_result') {
      $('feedback').textContent = event.status === 'accepted'
        ? '服务器已接受。此状态不代表所有观众已经收到。'
        : reasons[event.reason] || `发送被拒绝：${event.reason}`;
    } else if (event.type === 'error') {
      $('feedback').textContent = reasons[event.reason] || `请求错误：${event.reason}`;
      $('join-button').disabled = false;
      if (activeRoom) {
        desiredRoom = { ...activeRoom };
        $('room').value = activeRoom.roomId;
        $('nickname').value = activeRoom.nickname;
        enableSending(true);
      }
    } else if (event.type === 'pong') {
      lastPong = Date.now();
    }
  });
  current.addEventListener('close', () => {
    if (current !== socket) return;
    clearInterval(heartbeat);
    activeRoom = undefined;
    enableSending(false);
    $('join-button').disabled = false;
    if (stopped) { status('已断开'); return; }
    const delay = Math.random() * Math.min(30000, 1000 * 2 ** Math.min(reconnectAttempt++, 5));
    status(`${Math.ceil(delay / 1000)} 秒后重连`);
    reconnectTimer = setTimeout(connect, delay);
  });
  current.addEventListener('error', () => {
    $('feedback').textContent = '连接失败，请确认服务运行中。';
  });
}

$('join-form').addEventListener('submit', (event) => {
  event.preventDefault();
  desiredRoom = { roomId: $('room').value, nickname: $('nickname').value };
  if (socket?.readyState === WebSocket.OPEN) {
    enableSending(false);
    resetMessages();
    socket.send(JSON.stringify({ type: 'join', ...desiredRoom }));
  } else {
    if (socket?.readyState === WebSocket.CONNECTING) return;
    stopped = false;
    resetMessages();
    connect();
  }
});

$('disconnect').addEventListener('click', () => {
  stopped = true;
  clearTimeout(reconnectTimer);
  clearInterval(heartbeat);
  socket?.close();
  enableSending(false);
  status('已断开');
  $('disconnect').disabled = true;
  $('join-button').disabled = false;
  $('join-button').textContent = '连接并加入';
});

$('send-form').addEventListener('submit', (event) => {
  event.preventDefault();
  if (!joined || socket?.readyState !== WebSocket.OPEN) return;
  const message = JSON.stringify({ type: 'send', clientMessageId: crypto.randomUUID(),
    text: $('text').value, clientSentAt: Date.now() });
  if (new TextEncoder().encode(message).length > 1024) {
    $('feedback').textContent = reasons.frame_too_large;
    return;
  }
  socket.send(message);
  $('text').value = '';
  $('text').focus();
});
