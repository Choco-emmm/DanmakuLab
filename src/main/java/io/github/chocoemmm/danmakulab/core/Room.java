package io.github.chocoemmm.danmakulab.core;

import java.util.UUID;

public final class Room {
    private final String id;
    private final String instanceId = UUID.randomUUID().toString();
    private final TokenBucket inputLimiter;
    private long sequence;

    public Room(String id, int inputRate) {
        this.id = id;
        this.inputLimiter = new TokenBucket(inputRate);
    }

    public String id() {
        return id;
    }

    public String instanceId() {
        return instanceId;
    }

    public boolean admitMessage() {
        return inputLimiter.tryAcquire();
    }

    // Caller holds this room's monitor across sequence assignment and direct broadcast.
    public long nextSequence() {
        return ++sequence;
    }
}
