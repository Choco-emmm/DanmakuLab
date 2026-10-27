package io.github.chocoemmm.danmakulab.core;

import java.util.UUID;

/**
 * 一个非空房间的运行状态：名称、本次房间生命周期标识、输入额度和广播序号。
 * 成员列表统一由 ConnectionRegistry 管理，避免 Room 与 Registry 各存一份不一致的数据。
 */
public final class Room {
    // 客户端可见的房间名称，例如 lobby。
    private final String id;
    // 同名房间删除后重新创建会换一个 UUID，便于区分两段从 1 开始的序号。
    private final String instanceId = UUID.randomUUID().toString();
    // 房间内所有发送者共享同一个输入额度，控制整个房间的弹幕总输入。
    private final TokenBucket inputLimiter;
    // 只在持有当前 Room 对象锁时递增，锁约定见 nextSequence()。
    private long sequence;
    // 最后一位成员离开后标记为退休。volatile 让调度线程看到变化，不反向获取成员锁。
    // 同名新房间是另一个 Room；旧队列永远不能投递给新实例。
    private volatile boolean retired;

    public Room(String id, int inputRate) {
        this.id = id;
        this.inputLimiter = new TokenBucket(inputRate);
    }

    /** 返回房间名称，用于客户端加入、路由和显示。 */
    public String id() {
        return id;
    }

    /** 返回这一次房间生命周期的身份，与服务 runId、roomSeq 一起解释消息唯一范围。 */
    public String instanceId() {
        return instanceId;
    }

    /** 尝试取得一份房间级输入额度；它约束合计输入，不保证热点与其他房间完全隔离。 */
    public boolean admitMessage() {
        return inputLimiter.tryAcquire();
    }

    public boolean retired() { return retired; }

    /** Registry 在自己的成员锁内调用；这里只改标记，不回调调度器，避免锁的环形等待。 */
    void retire() { retired = true; }

    /** 持有 Room 锁时预看下一个序号；容量检查成功后才用 nextSequence 正式占用。 */
    long sequenceCandidate() { return sequence + 1; }

    /**
     * 为本房间生成下一个广播序号。
     * direct 调用方在 synchronized(room) 内串行广播；fair 在同一锁内分配序号并入 FIFO，
     * 再由唯一房间调度线程按队列顺序处理整条广播，不能在前一条未遍历完时开始后一条。
     * 如果只给递增操作加锁，两条广播仍可能交错，导致观众先看到 2、后看到 1。
     */
    public long nextSequence() {
        return ++sequence;
    }
}
