package io.github.chocoemmm.danmakulab.core;

import java.util.function.LongSupplier;

/**
 * 令牌桶限流器：每条弹幕消耗一个令牌，额度不够就立即拒绝，不在这里排队等待。
 *
 * 例如 rate=5：桶容量为 5，初始允许连续发 5 条，耗尽后约每 200ms 恢复一个额度。
 * 这允许短时突发，同时限制长期平均速率；不保证任意一秒窗口里严格最多 5 条。
 *
 * 使用“按需补充”：每次尝试取令牌时才根据经过的时间计算余额，
 * 因此每个连接、每个房间都可以有自己的桶，而无需各自创建线程或定时任务。
 */
public final class TokenBucket {
    // 每秒补充的令牌数。本实现把桶容量也设为 rate，即最多积累一秒的额度。
    private final int rate;
    // 返回当前纳秒时间的函数。通过注入时钟，测试可以直接推进时间而不用真实等待。
    private final LongSupplier clock;
    // 用 double 保留不足一个令牌的余额，例如 0.5 个令牌；达到 1 才能通过一次请求。
    private double tokens;
    // 上次计算补充量的时间，单位为纳秒；只在同一个单调时钟内比较差值。
    private long lastRefill;

    /** 正常业务使用的构造器，选择不受系统日期校正影响的单调时钟。 */
    public TokenBucket(int rate) {
        // 方法引用 System::nanoTime 表示“需要时间时调用 System.nanoTime()”。
        this(rate, System::nanoTime);
    }

    /** 包内构造器允许测试传入可控时钟；初始桶是满的，因此刚建立连接也能小幅突发。 */
    TokenBucket(int rate, LongSupplier clock) {
        this.rate = rate;
        this.clock = clock;
        this.tokens = rate;
        this.lastRefill = clock.getAsLong();
    }

    /**
     * 尝试取走一个令牌：true 代表允许，false 代表限流。
     * synchronized 锁住当前桶实例，把“计算余额、检查余额、扣减”作为一个整体执行。
     * 否则两个线程可能同时看到最后一个令牌，并把两条消息都放行。
     */
    public synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        // 纳秒差除以 10 亿转换为秒，再乘每秒速率，得到这段时间应补的令牌数。
        // Math.min 限制桶的最大余额，防止空闲很久后一次性积累无限发送额度。
        tokens = Math.min(rate, tokens + (now - lastRefill) / 1_000_000_000.0 * rate);
        // 本次新增额度已经入账；即使本次拒绝，也必须更新时间，避免下次重复补同一段时间。
        lastRefill = now;
        if (tokens < 1) {
            // 返回拒绝，不睡眠、不等待；由上层返回明确的限流原因。
            return false;
        }
        // 确认额度足够后再扣一个，代表本次消息已使用这份准入额度。
        tokens--;
        return true;
    }
}
