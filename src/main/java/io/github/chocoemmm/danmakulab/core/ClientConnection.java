package io.github.chocoemmm.danmakulab.core;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * 一条物理 WebSocket 连接的业务包装：持有 Session、该连接自己的输入限流器及统一发送入口。
 * 同一用户打开两个页面会建立两个连接；当前没有账号系统，所以按连接 ID 管理。
 * direct 模式直接写，buffered 模式只入有界邮箱；两者共用 writeNow 作为实际网络写入口。
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
    private boolean disposed;
    private boolean closeRequested;

    public ClientConnection(WebSocketSession session, int inputRate) {
        this(session, inputRate, null, () -> { });
    }

    ClientConnection(WebSocketSession session, int inputRate, OutboundService outbound, Runnable invalidate) {
        this.session = session;
        this.inputLimiter = new TokenBucket(inputRate);
        this.outbound = outbound;
        this.invalidate = invalidate;
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

    /** 每连接一把实际写锁；邮箱调度也保证单写者，锁仍为直接模式和底层调用提供保护。 */
    void writeNow(TextMessage message) throws IOException {
        synchronized (writeLock) {
            if (!session.isOpen()) throw new IOException("Connection is closed");
            session.sendMessage(message);
        }
    }

    /** Registry 的逻辑注销只清理内存，不在成员锁里执行网络关闭。 */
    void dispose() {
        synchronized (lifecycleLock) {
            if (disposed) return;
            disposed = true;
            if (mailbox != null) mailbox.cancel();
            // 若已请求异步关闭，名额由关闭任务 finally 归还；正常断开则立即归还。
            if (outbound != null && !closeRequested) outbound.retire(this);
        }
    }

    void expirePending() {
        if (mailbox != null) mailbox.expire();
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
