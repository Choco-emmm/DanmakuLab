package io.github.chocoemmm.danmakulab.core;

import java.util.function.LongSupplier;

/** Monotonic clock; one second of burst capacity. No timer or task per bucket. */
public final class TokenBucket {
    private final int rate;
    private final LongSupplier clock;
    private double tokens;
    private long lastRefill;

    public TokenBucket(int rate) {
        this(rate, System::nanoTime);
    }

    TokenBucket(int rate, LongSupplier clock) {
        this.rate = rate;
        this.clock = clock;
        this.tokens = rate;
        this.lastRefill = clock.getAsLong();
    }

    public synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        tokens = Math.min(rate, tokens + (now - lastRefill) / 1_000_000_000.0 * rate);
        lastRefill = now;
        if (tokens < 1) {
            return false;
        }
        tokens--;
        return true;
    }
}
