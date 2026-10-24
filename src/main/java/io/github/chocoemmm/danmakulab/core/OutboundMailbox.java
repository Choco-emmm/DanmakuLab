package io.github.chocoemmm.danmakulab.core;

import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;

/**
 * 一条连接的有界输出邮箱（Mailbox）：广播线程只投递，发送线程稍后实际写网络。
 * 所有状态由此对象的监视器保护，但网络 I/O 永远在锁外，慢发送不会挡住其他入队与注销。
 * 预算包括队列和 inFlight（已经取出、发送尚未返回的一条）；不能一取出就假装释放内存。
 * active 标志表示“已安排或正在执行一个发送者”，同连接不会同时出现两个写入者。
 */
final class OutboundMailbox {
    private final ClientConnection connection;
    private final OutboundService service;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private long bytes;
    private int count;
    private boolean active;
    private boolean closed;

    OutboundMailbox(ClientConnection connection, OutboundService service) {
        this.connection = connection;
        this.service = service;
    }

    /** record 是不可变消息条目；同一只读 TextMessage 可共享，预算仍按每份投递计算。 */
    private record Entry(TextMessage message, boolean danmaku, long queuedAt, int bytes) { }

    /**
     * 将完整 JSON 入队。只允许移除未发送的普通弹幕，控制回复绝不静默丢弃。
     * 条数/字节满时优先淘汰最旧弹幕；无法腾空间或全局预算不足时关闭本连接。
     * 返回仅表示入队成功，不表示 sendMessage 已返回，更不表示客户端已收到。
     */
    void offer(TextMessage message, boolean danmaku) throws IOException {
        String failure = null;
        boolean start = false;
        int size = message.getPayloadLength();
        synchronized (this) {
            if (closed) throw new IOException("Outbound mailbox is closed");
            evictExpired(System.nanoTime());
            if (size > service.properties.maxBytes()) {
                failure = "message_too_large";
            } else {
                while (count >= service.properties.maxMessages() || bytes > service.properties.maxBytes() - size) {
                    Entry oldest = null;
                    // 心跳/加入/发送结果可能夹在弹幕中，用迭代器找最旧可丢弹幕，不移动控制回复。
                    var iterator = queue.iterator();
                    while (iterator.hasNext()) {
                        var entry = iterator.next();
                        if (entry.danmaku()) { oldest = entry; iterator.remove(); break; }
                    }
                    if (oldest == null) { failure = "mailbox_full"; break; }
                    release(oldest);
                    service.event("lab.outbound.dropped", "overflow");
                }
                if (failure == null) {
                    if (!service.reserve(size)) {
                        failure = "global_budget";
                    } else {
                        queue.addLast(new Entry(message, danmaku, System.nanoTime(), size));
                        bytes += size;
                        count++;
                        if (danmaku) service.metrics.counter("lab.deliveries.enqueued").increment();
                        if (!active) { active = true; start = true; }
                    }
                }
            }
        }
        if (failure != null) {
            connection.fail(failure);
            throw new IOException("Outbound rejected: " + failure);
        }
        if (start) schedule();
    }

    /** 提交的任务只有“激活连接”这一种粒度，不会每份投递各堆一个执行器任务。 */
    private void schedule() throws IOException {
        try {
            service.schedule(this::drain);
        } catch (RejectedExecutionException rejected) {
            connection.fail("executor_rejected");
            throw new IOException("Outbound executor rejected connection", rejected);
        }
    }

    /** 一个批次最多发送 batchSize 条，然后重新排到共享池尾部；尚未实现房间入口公平调度。 */
    private void drain() {
        for (int i = 0; i < service.properties.batchSize(); i++) {
            Entry entry;
            boolean controlExpired;
            synchronized (this) {
                if (closed) { active = false; return; }
                evictExpired(System.nanoTime());
                entry = queue.pollFirst();
                if (entry == null) { active = false; return; }
                controlExpired = !entry.danmaku() && expired(entry, System.nanoTime());
                // 已移出队列但仍占预算，直到 finally 中 release；注销只清队列，不碰此条。
            }
            boolean failed = false;
            try {
                if (controlExpired) {
                    connection.fail("control_expired");
                    failed = true;
                } else {
                    connection.writeNow(entry.message());
                    if (entry.danmaku()) service.metrics.counter("lab.deliveries.submitted").increment();
                }
            } catch (IOException | RuntimeException writeFailure) {
                service.metrics.counter("lab.writes.failed").increment();
                connection.fail("write_failure");
                failed = true;
            } finally {
                synchronized (this) { release(entry); }
            }
            if (failed) return;
        }
        synchronized (this) {
            if (closed || queue.isEmpty()) { active = false; return; }
            // 继续保留 active=true，新入队无需再提交第二个任务。
        }
        try { schedule(); } catch (IOException ignored) { /* schedule 已注销连接并记录拒绝原因。 */ }
    }

    /** 周期清理仅涉及未发送条目；传输层里的数据不能撤回。控制回复超龄直接关闭连接。 */
    void expire() {
        boolean fail = false;
        synchronized (this) {
            if (closed) return;
            long now = System.nanoTime();
            evictExpired(now);
            for (var entry : queue) {
                if (!entry.danmaku() && expired(entry, now)) { fail = true; break; }
            }
        }
        if (fail) connection.fail("control_expired");
    }

    private boolean expired(Entry entry, long now) {
        return now - entry.queuedAt() >= service.properties.maxAgeMillis() * 1_000_000L;
    }

    private void evictExpired(long now) {
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.danmaku() && expired(entry, now)) {
                iterator.remove();
                release(entry);
                service.event("lab.outbound.dropped", "expired");
            }
        }
    }

    /** 调用方持有邮箱锁；一条预算只能从排队、过期、丢弃或发送完成其中一个路径释放。 */
    private void release(Entry entry) {
        bytes -= entry.bytes();
        count--;
        service.release(entry.bytes());
    }

    synchronized void cancel() {
        if (closed) return;
        closed = true;
        while (!queue.isEmpty()) {
            var entry = queue.removeFirst();
            release(entry);
            service.event("lab.outbound.discarded", "connection_closed");
        }
    }
}
