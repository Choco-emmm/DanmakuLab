'use strict';
/**
 * 演示页交互入口：管理连接、加入房间、心跳、重连、发送和纯文本展示。
 * 旧连接的异步事件可能晚于新连接到达，下面通过 current === socket 判断事件归属。
 */
// 按元素 ID 取 DOM 的简写，例如 $('text') 取得弹幕输入框。
const $ = (id) => document.getElementById(id);
// socket 为当前连接；desiredRoom 为想加入的房间，activeRoom 为服务端已确认加入的房间。
// 分开保存两者，使换房被拒绝后仍能恢复原房间，而不把“想加入”误当成“已经加入”。
let socket;
let desiredRoom;
let activeRoom;
// stopped 表示用户主动停止，关闭后不重连；joined 表示已收到加入确认，决定能否发送。
let stopped = true;
let joined = false;
// 失败次数影响退避上限；两个 timer 分别负责重连与应用心跳，结束时都要清理。
let reconnectAttempt = 0;
let reconnectTimer;
let heartbeat;
// received 是页面累计接收数；lastPong 是最近一次收到心跳回应的本机毫秒时间。
let received = 0;
let lastPong = 0;

// 服务端原因码到中文提示的映射；未知原因仍显示原码，方便定位协议错误。
const reasons = {
  connection_rate_limit: '发送太快，请稍后重试。',
  room_rate_limit: '房间消息过多，请稍后重试。',
  room_limit: '房间数量已到上限。',
  not_joined: '请先加入房间。',
  invalid_text: '弹幕不能为空，且最多 200 个字符。',
  frame_too_large: '完整消息超过 1 KiB。',
};

/** 更新连接状态文字与状态点；data 属性由 CSS 用来切换颜色。 */
function status(label, connected = false) {
  $('status').dataset.connected = String(connected);
  $('status').querySelector('span').textContent = label;
}

/** 控制输入框和发送按钮，只有加入确认后才启用；连接建立本身还不代表有房间。 */
function enableSending(enabled) {
  joined = enabled;
  $('text').disabled = !enabled;
  $('send-button').disabled = !enabled;
}

/** 主动加入或换房时清空当前展示，避免把不同房间的消息混在一份列表中。 */
function resetMessages() {
  $('messages').replaceChildren();
  received = 0;
  $('received').textContent = '0 条已接收';
}

/** 将一条弹幕转换为 DOM，正文和昵称都以纯文本写入。 */
function appendMessage(event) {
  const row = document.createElement('article');
  row.className = 'message';
  const meta = document.createElement('div');
  meta.className = 'message-meta';
  const nickname = document.createElement('strong');
  // textContent 不把用户输入解释为 HTML，例如 <img ...> 只显示文字，不创建图片标签。
  nickname.textContent = event.nickname;
  const sequence = document.createElement('span');
  sequence.textContent = `#${event.roomSeq}`;
  const body = document.createElement('p');
  body.textContent = event.text;
  meta.append(nickname, sequence);
  row.append(meta, body);
  $('empty')?.remove();
  $('messages').append(row);
  // 页面最多保留 200 条 DOM，避免演示越久节点越多；received 仍累计全部接收数。
  // 删除展示节点不等于网络接收变慢，因此这不是后续压测中的 TCP 慢消费者模拟。
  while ($('messages').children.length > 200) $('messages').firstElementChild.remove();
  $('messages').scrollTop = $('messages').scrollHeight;
  $('received').textContent = `${++received} 条已接收`;
}

/** 建立新连接；同时用于首次连接和异常断线后的自动重连。 */
function connect() {
  // 先清理上一轮 timer，避免重复心跳或一次断线触发多个重连任务。
  clearTimeout(reconnectTimer);
  clearInterval(heartbeat);
  enableSending(false);
  status(reconnectAttempt ? '正在重连…' : '正在连接…');
  $('join-button').disabled = true;
  $('disconnect').disabled = false;
  // HTTPS 页面使用加密的 wss，HTTP 页面使用 ws；复用当前页面地址以满足同源策略。
  const current = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`);
  socket = current;
  current.addEventListener('open', () => {
    // 旧连接晚到的 open 或用户已点“断开”时，立即关闭它，不再加入房间。
    if (current !== socket || stopped) { current.close(); return; }
    current.send(JSON.stringify({ type: 'join', ...desiredRoom }));
    lastPong = Date.now();
    heartbeat = setInterval(() => {
      if (current.readyState !== WebSocket.OPEN) return;
      // 15 秒检查一次；若距上次 pong 超过 45 秒，则主动关闭以进入重连流程。
      if (Date.now() - lastPong > 45000) { current.close(); return; }
      current.send(JSON.stringify({ type: 'ping' }));
    }, 15000);
  });
  current.addEventListener('message', ({ data }) => {
    // 防止已被替换的连接用延迟到达的消息修改新连接的界面状态。
    if (current !== socket) return;
    const event = JSON.parse(data);
    if (event.type === 'joined') {
      // 服务端确认后才更新实际房间、重置失败次数并开放发送。
      // 展开语法 {...desiredRoom} 复制字段，避免两份状态直接共享同一对象。
      activeRoom = { ...desiredRoom };
      reconnectAttempt = 0;
      status(`已连接 / ${event.roomId}`, true);
      $('messages-title').textContent = `房间 / ${event.roomId}`;
      $('join-button').disabled = false;
      $('join-button').textContent = '切换房间';
      $('feedback').textContent = '已加入房间。断线期间的弹幕不会补发。';
      enableSending(true);
    } else if (event.type === 'danmaku' && event.roomId === activeRoom?.roomId) {
      // ?. 表示 activeRoom 不存在时返回 undefined；只展示实际所在房间的弹幕。
      appendMessage(event);
    } else if (event.type === 'send_result') {
      // accepted 只表示服务器接纳，不能当作所有观众均已收到的凭证。
      $('feedback').textContent = event.status === 'accepted'
        ? '服务器已接受。此状态不代表所有观众已经收到。'
        : reasons[event.reason] || `发送被拒绝：${event.reason}`;
    } else if (event.type === 'error') {
      $('feedback').textContent = reasons[event.reason] || `请求错误：${event.reason}`;
      $('join-button').disabled = false;
      if (activeRoom) {
        // 换房失败时服务端保留原成员关系；界面也回到原房间并恢复发送。
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
    // 指数退避上限约为 1、2、4、8、16、30 秒，再乘随机数加入抖动。
    // 实际等待在 0 与该上限之间，避免许多断线客户端总在同一时刻集体重连。
    const delay = Math.random() * Math.min(30000, 1000 * 2 ** Math.min(reconnectAttempt++, 5));
    status(`${Math.ceil(delay / 1000)} 秒后重连`);
    reconnectTimer = setTimeout(connect, delay);
  });
  current.addEventListener('error', () => {
    $('feedback').textContent = '连接失败，请确认服务运行中。';
  });
}

// 表单默认提交会刷新页面；preventDefault 后改由 WebSocket 协议完成加入或换房。
$('join-form').addEventListener('submit', (event) => {
  event.preventDefault();
  desiredRoom = { roomId: $('room').value, nickname: $('nickname').value };
  if (socket?.readyState === WebSocket.OPEN) {
    // 已有连接时复用它发送 join，而不是为每次换房再开一条连接。
    enableSending(false);
    resetMessages();
    socket.send(JSON.stringify({ type: 'join', ...desiredRoom }));
  } else {
    if (socket?.readyState === WebSocket.CONNECTING) return;
    // 仍在建立连接时不重复创建；其余状态走重新连接流程。
    stopped = false;
    resetMessages();
    connect();
  }
});

// 主动断开必须先设置 stopped，随后到达的 close 回调才能知道这次无需重连。
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

// 附带唯一关联 ID 和本机时间戳，服务于诊断与统计，不保证去重或交付。
$('send-form').addEventListener('submit', (event) => {
  event.preventDefault();
  if (!joined || socket?.readyState !== WebSocket.OPEN) return;
  const message = JSON.stringify({ type: 'send', clientMessageId: crypto.randomUUID(),
    text: $('text').value, clientSentAt: Date.now() });
  // 必须计算完整 JSON 的 UTF-8 大小，不能只依据输入框的字符数。
  // 浏览器提前提示是用户体验优化；服务端仍会独立校验，不能信任客户端检查。
  if (new TextEncoder().encode(message).length > 1024) {
    $('feedback').textContent = reasons.frame_too_large;
    return;
  }
  socket.send(message);
  $('text').value = '';
  $('text').focus();
});
