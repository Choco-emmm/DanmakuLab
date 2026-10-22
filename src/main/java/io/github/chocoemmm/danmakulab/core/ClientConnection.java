package io.github.chocoemmm.danmakulab.core;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

public final class ClientConnection {
    private final WebSocketSession session;
    private final TokenBucket inputLimiter;

    public ClientConnection(WebSocketSession session, int inputRate) {
        this.session = session;
        this.inputLimiter = new TokenBucket(inputRate);
    }

    public String id() {
        return session.getId();
    }

    public boolean admitMessage() {
        return inputLimiter.tryAcquire();
    }

    /** All application writes, including control replies, use this same monitor. */
    public synchronized void send(TextMessage message) throws IOException {
        if (!session.isOpen()) {
            throw new IOException("Connection is closed");
        }
        session.sendMessage(message);
    }

    public void closeAfterFailure() {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // Registry removal is independent of transport close succeeding.
        }
    }
}
