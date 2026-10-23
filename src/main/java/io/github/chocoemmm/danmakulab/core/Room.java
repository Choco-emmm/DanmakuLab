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

    /**
     * 为本房间生成下一个广播序号。
     * 调用方必须在 synchronized(room) 内，把“取序号 + 整次广播”一起串行化。
     * 如果只给递增操作加锁，两条广播仍可能交错，导致观众先看到 2、后看到 1。
     */
    public long nextSequence() {
        return ++sequence;
    }
}
