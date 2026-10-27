package io.github.chocoemmm.danmakulab.core;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * 一条物理 WebSocket 连接的业务包装：持有 Session、该连接自己的输入限流器及统一发送入口。
 * 同一用户打开两个页面会建立两个连接；当前没有账号系统，所以按连接 ID 管理。
 * direct 模式保留同步 writeNow 基线；buffered 模式只入邮箱，由原生异步端点写网络。
 * 异步仅改变等待方式，不表示可靠送达，也不允许同连接同时提交多条在途消息。
 */
public final class ClientConnection {
    // Spring 提供的真实连接对象，最终由它把消息交给 WebSocket 容器。
    private final WebSocketSession session;
    // 只约束本连接发入服务端的弹幕数量，不限制其他连接，也不限制服务端向观众的输出。
    private final TokenBucket inputLimiter;
    private final Object writeLock = new Object();
    // 生命周期锁只保护注销/关闭请求的先后关系，绝不在持有它时做网络 I/O。
    private final Object lifecycleLock = new Object();
    private final OutboundService outbound;
    private final OutboundMailbox mailbox;
    private final Runnable invalidate;
    // 只有 buffered 模式持有此端点。容器通过完成回调通知结果，不占共享线程等网络可写。
    private final RemoteEndpoint.Async asyncRemote;
    private boolean disposed;
    private boolean closeRequested;
    private boolean closeFinished;

    public ClientConnection(WebSocketSession session, int inputRate) {
        this(session, inputRate, null, () -> { });
    }

    ClientConnection(WebSocketSession session, int inputRate, OutboundService outbound, Runnable invalidate) {
        this.session = session;
        this.inputLimiter = new TokenBucket(inputRate);
        this.outbound = outbound;
        this.invalidate = invalidate;
        if (outbound == null) {
            asyncRemote = null;
        } else {
            if (!(session instanceof NativeWebSocketSession nativeSession)
                    || !(nativeSession.getNativeSession() instanceof Session jakartaSession)) {
                // 本项目使用 Jakarta/Tomcat。禁止悄悄回退成同步写，再次引入共享线程饥饿。
                throw new IllegalArgumentException("buffered output requires a native Jakarta WebSocket session");
            }
            asyncRemote = jakartaSession.getAsyncRemote();
            // 单次传输等待最多5秒，单位毫秒；邮箱年龄1秒是另一层排队限制，二者不能混用。
            asyncRemote.setSendTimeout(5_000L);
        }
        this.mailbox = outbound == null ? null : new OutboundMailbox(this, outbound);
    }

    /** 使用服务端分配的 Session ID，避免相信客户端自己声称的身份。 */
    public String id() {
        return session.getId();
    }

    /** 消耗本连接的一个输入额度；上层还会继续检查整个房间的合计额度。 */
    public boolean admitMessage() {
        return inputLimiter.tryAcquire();
    }

    /**
     * 所有应用消息都经过这里，包括弹幕、加入响应、发送结果、错误和 pong。
     * 控制消息使用本重载；普通弹幕由广播器显式传 danmaku=true，便于区分可丢与不可丢。
     */
    public void send(TextMessage message) throws IOException {
        send(message, false);
    }

    public void send(TextMessage message, boolean danmaku) throws IOException {
        if (mailbox == null) writeNow(message);
        else mailbox.offer(message, danmaku);
    }

    public boolean buffered() { return mailbox != null; }

    /** 直接模式的同步网络写入口；保留逐连接写锁，作为优化前基线，不用于buffered邮箱。 */
    void writeNow(TextMessage message) throws IOException {
        synchronized (writeLock) {
            if (!session.isOpen()) throw new IOException("Connection is closed");
            session.sendMessage(message);
        }
    }

    /**
     * 邮箱一次只提交一条。此锁仅保护发起调用，不跨异步等待；单写者由邮箱的 inFlight 保证。
     * completion 为“容器完成或失败”的回调，可能在 sendText 返回前就被调用，邮箱必须兼容。
     * 不能调用 Future.get/join 等待结果，否则会把异步写重新变成共享线程阻塞。
     */
    void writeAsync(TextMessage message, Consumer<Throwable> completion) throws IOException {
        synchronized (writeLock) {
            if (!session.isOpen()) throw new IOException("Connection is closed");
            asyncRemote.sendText(message.getPayload(), result -> completion.accept(result.getException()));
        }
    }

    /** Registry 的逻辑注销只清理内存，不在成员锁里执行网络关闭。 */
    void dispose() {
        synchronized (lifecycleLock) {
            if (disposed) return;
            disposed = true;
            if (mailbox != null) mailbox.cancel();
            retireIfFinished();
        }
    }

    void expirePending() {
        if (mailbox != null) mailbox.expire();
    }

    /** 关闭任务结束与写完成可能以任意顺序发生；两者都结束后才能复用连接名额。 */
    void closeFinished() {
        synchronized (lifecycleLock) {
            closeFinished = true;
            retireIfFinished();
        }
    }

    void writeFinished() {
        synchronized (lifecycleLock) { retireIfFinished(); }
    }

    /** 调用方持有生命周期锁；在途消息仍保留预算和名额，避免断开/重连制造超上限在途写。 */
    private void retireIfFinished() {
        if (outbound != null && disposed && (!closeRequested || closeFinished) && !mailbox.hasInFlight()) {
            outbound.retire(this);
        }
    }

    /**
     * 失败回调可重复触发，但只注销、记录和提交关闭一次，避免重复释放预算/名额。
     * 标记关闭到提交关闭任务必须在同一把生命周期锁中完成：停机线程也会调用 fail，
     * 它应等待首次 fail 完成提交后，才能停止关闭池，不能只看到标志就提前结束。
     * invalidate 只注销成员和清理内存，closeLater 只提交有界任务，实际网络关闭在独立池执行。
     */
    void fail(String reason) {
        synchronized (lifecycleLock) {
            if (disposed || closeRequested) return;
            closeRequested = true;
            outbound.event("lab.outbound.closed", reason);
            // Registry.remove 退出成员锁后回调 dispose；同线程重入 lifecycleLock 是允许的。
            invalidate.run();
            // closeRequested 已置位，Registry.remove -> dispose 不会提前归还名额。
            outbound.closeLater(this, this::closeSession);
        }
    }

    /**
     * 发送失败后的兜底关闭：直接模式由调用方先注销，再同步关闭；
     * 缓冲模式通过 fail 内部注销并提交异步关闭，避免广播线程等待网络关闭。
     */
    public void closeAfterFailure() {
        if (buffered()) { fail("write_failure"); return; }
        closeSession();
    }

    private void closeSession() {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // 连接可能已经断开；关闭失败也不应阻止其他观众继续接收，Registry 清理已先完成。
        }
    }
}
