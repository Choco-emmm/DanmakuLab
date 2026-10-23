package io.github.chocoemmm.danmakulab.core;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * 一条物理 WebSocket 连接的业务包装：持有 Session、该连接自己的输入限流器及统一发送入口。
 * 同一用户打开两个页面会建立两个连接；当前没有账号系统，所以按连接 ID 管理。
 * 当前发送仍是同步基线，这里还没有待发队列或慢消费者丢弃策略。
 */
public final class ClientConnection {
    // Spring 提供的真实连接对象，最终由它把消息交给 WebSocket 容器。
    private final WebSocketSession session;
    // 只约束本连接发入服务端的弹幕数量，不限制其他连接，也不限制服务端向观众的输出。
    private final TokenBucket inputLimiter;

    public ClientConnection(WebSocketSession session, int inputRate) {
        this.session = session;
        this.inputLimiter = new TokenBucket(inputRate);
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
     * synchronized 锁住这条连接，保证同一时刻只有一个线程调用 Session 的发送方法。
     * 仅保护弹幕而让心跳绕开这把锁，仍可能发生同一个 Session 被并发写入的问题。
     * 本锁只保护一条连接；其他连接各有自己的锁。发送慢时当前调用线程仍会等待。
     */
    public synchronized void send(TextMessage message) throws IOException {
        if (!session.isOpen()) {
            // 检查与实际发送之间仍可能断连，因此调用方还需要处理发送时的异常。
            throw new IOException("Connection is closed");
        }
        session.sendMessage(message);
    }

    /** 发送失败后的兜底关闭；调用方已经先从 Registry 注销，避免继续向失效连接分发。 */
    public void closeAfterFailure() {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // 连接可能已经断开；关闭失败也不应阻止其他观众继续接收，Registry 清理已先完成。
        }
    }
}
