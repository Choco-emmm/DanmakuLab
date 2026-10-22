package io.github.chocoemmm.danmakulab.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {
    @Test
    void limitsBurstAndRefillsUsingElapsedTime() {
        var clock = new AtomicLong();
        var bucket = new TokenBucket(2, clock::get);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
        clock.set(499_000_000);
        assertThat(bucket.tryAcquire()).isFalse();
        clock.set(500_000_000);
        assertThat(bucket.tryAcquire()).isTrue();
        clock.set(20_000_000_000L);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }
}
