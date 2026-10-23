package io.github.chocoemmm.danmakulab.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 用可控纳秒时钟验证限流边界，不依赖 Thread.sleep 或机器调度速度。 */
class TokenBucketTest {
    @Test
    void limitsBurstAndRefillsUsingElapsedTime() {
        // AtomicLong 保存测试时间；clock::get 让令牌桶读取我们指定的值。
        var clock = new AtomicLong();
        var bucket = new TokenBucket(2, clock::get);
        // 初始桶容量为 2：前两次通过，第三次因耗尽拒绝。
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
        clock.set(499_000_000);
        // 2 个/秒意味着每 500ms 恢复 1 个；499ms 尚不足，500ms 恰好可以放行。
        assertThat(bucket.tryAcquire()).isFalse();
        clock.set(500_000_000);
        assertThat(bucket.tryAcquire()).isTrue();
        clock.set(20_000_000_000L);
        // 空闲很久仍最多恢复到容量 2，不能把 20 秒额度全部积累起来。
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }
}
