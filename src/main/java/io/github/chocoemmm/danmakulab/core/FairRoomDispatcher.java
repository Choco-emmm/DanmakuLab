package io.github.chocoemmm.danmakulab.core;

import io.github.chocoemmm.danmakulab.config.DispatchProperties;
import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.config.OutboundProperties;
import io.github.chocoemmm.danmakulab.protocol.ProtocolException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 房间入口与轮转调度：房间各有一个有界 FIFO，所有活跃房间共享一个就绪队列和一个平台线程。
 * 例如热点有 500 位观众、一轮最多投递 64 份，便保留遍历位置，把该房间排到其他就绪房间后面。
 * 同房间前一条尚未遍历完，绝不开始后一条；这里的公平是有限工作量的轮转，不是延迟 SLA。
 *
 * monitor 只保护队列、预算和调度状态；成员快照、观众邮箱投递、控制确认均在它之外执行。
 * fair 强制要求 buffered 输出，所以调度线程不执行 Socket 写入，也不采用 CallerRuns 回退。
 * 队列中只保存编码后的不可变广播；每个正在分发的房间最多额外持有一个成员快照。
 */
@Component
public class FairRoomDispatcher {
    private static final Logger log = LoggerFactory.getLogger(FairRoomDispatcher.class);
    private final DispatchProperties properties;
    private final int roomLimit;
    private final ConnectionRegistry registry;
    private final DirectBroadcaster broadcaster;
    private final MeterRegistry metrics;
    private final LongSupplier clock;
    private final Object monitor = new Object();
    // 按 Room 对象身份隔离同名房间的不同生命周期；空队列完成收尾后立即移除状态。
    private final Map<Room, RoomQueue> rooms = new HashMap<>();
    private final ArrayDeque<RoomQueue> ready = new ArrayDeque<>();
    private final Thread worker;
    private boolean stopping;
    private long bytes;
    private long messages;
    private long highWater;

    /** Spring 只调用此构造器；依赖 OutboundProperties 用于启动校验，不另创建输出线程池。 */
    @Autowired
    public FairRoomDispatcher(DispatchProperties properties, LabProperties lab, OutboundProperties outbound,
                              ConnectionRegistry registry, DirectBroadcaster broadcaster, MeterRegistry metrics) {
        this(properties, lab, outbound, registry, broadcaster, metrics, System::nanoTime, true);
    }

    /** 测试可关闭自动线程，手动执行一轮并注入假时钟，准确重现队列满、轮转和过期。 */
    FairRoomDispatcher(DispatchProperties properties, LabProperties lab, OutboundProperties outbound,
                       ConnectionRegistry registry, DirectBroadcaster broadcaster, MeterRegistry metrics,
                       LongSupplier clock, boolean autoStart) {
        if (properties.fair() && !outbound.buffered()) {
            throw new IllegalArgumentException("lab.dispatch.mode=fair requires lab.outbound.mode=buffered");
        }
        this.properties = properties;
        this.roomLimit = lab.maxRooms();
        this.registry = registry;
        this.broadcaster = broadcaster;
        this.metrics = metrics;
        this.clock = clock;
        metrics.gauge("lab.dispatch.bytes", this, FairRoomDispatcher::bufferedBytes);
        metrics.gauge("lab.dispatch.messages", this, FairRoomDispatcher::bufferedMessages);
        metrics.gauge("lab.dispatch.bytes.highwater", this, value -> value.highWater());
        metrics.gauge("lab.dispatch.rooms", this, value -> value.roomCount());
        metrics.gauge("lab.dispatch.ready", this, value -> value.readyCount());
        metrics.gauge("lab.dispatch.mode.fair", properties.fair() ? 1 : 0);
        if (properties.fair() && autoStart) {
            worker = new Thread(this::run, "lab-room-dispatch");
            worker.setDaemon(true);
            worker.start();
        } else worker = null;
    }

    public boolean enabled() { return properties.fair(); }

    /**
     * 通过两级令牌桶后调用：检查房间/全局预算，编码一次，正式分配序号并追加到 FIFO。
     * 拒绝不占用序号、不增加 accepted；先前消耗的令牌不退还，与现有限流策略保持一致。
     * accepted 表示这一条确实进入过房间队列；随后仍可能过期、取消或输出丢弃，不承诺交付。
     */
    public void submit(ClientConnection sender, ConnectionRegistry.Membership membership,
                       String id, String text, long sentAt) throws IOException {
        if (!enabled()) throw new IllegalStateException("Fair dispatch is disabled");
        long sequence;
        var room = membership.room();
        synchronized (monitor) {
            if (stopping) throw new ProtocolException("server_stopping");
            if (room.retired()) throw new ProtocolException("room_closed");
            var queue = rooms.get(room);
            if (queue == null && rooms.size() >= roomLimit) throw new ProtocolException("room_scheduler_full");
            if (queue != null && queue.entries.size() >= properties.maxMessages()) throw new ProtocolException("room_queue_full");
            if (messages >= properties.globalMessages()) throw new ProtocolException("room_global_budget");
            // 只在短临界区内预看/提交序号，绝不把成员锁、邮箱或网络发送带入这把锁。
            synchronized (room) {
                sequence = room.sequenceCandidate();
                var message = broadcaster.prepare(sender, membership, id, text, sentAt, sequence);
                int size = message.getPayloadLength();
                if (size > properties.maxBytes() - (queue == null ? 0 : queue.bytes)) throw new ProtocolException("room_queue_full");
                if (size > properties.globalBytes() - bytes) throw new ProtocolException("room_global_budget");
                room.nextSequence();
                if (queue == null) {
                    queue = new RoomQueue(room);
                    rooms.put(room, queue);
                }
                queue.entries.addLast(new Pending(message, clock.getAsLong()));
                queue.bytes += size;
                bytes += size;
                messages++;
                highWater = Math.max(highWater, bytes);
                metrics.counter("lab.messages.accepted").increment();
                if (!queue.scheduled) {
                    queue.scheduled = true;
                    ready.addLast(queue);
                    monitor.notifyAll();
                }
            }
        }
        // 放锁后才入控制邮箱；调度线程可能已分发弹幕，所以客户端不能假设确认一定先到。
        broadcaster.reply(sender, Map.of("type", "send_result", "status", "accepted",
                "clientMessageId", id, "roomSeq", sequence));
    }

    /**
     * 跨节点输入已经由来源节点编码、分配身份；这里仅占本地预算，不分配新序号、不回复来源者。
     * 房间对象绑定本地生命周期，不能把排队期间退休的旧Room改投到同名新Room。
     */
    public boolean acceptBroadcast(String roomId, org.springframework.web.socket.TextMessage message) {
        var room = registry.currentRoom(roomId);
        if (room == null) return false;
        synchronized (monitor) {
            if (stopping) throw new io.github.chocoemmm.danmakulab.protocol.ProtocolException("server_stopping");
            if (room.retired()) return false;
            var queue = rooms.get(room);
            if (queue == null && rooms.size() >= roomLimit) throw new ProtocolException("room_scheduler_full");
            if (queue != null && queue.entries.size() >= properties.maxMessages()) throw new ProtocolException("room_queue_full");
            int size = message.getPayloadLength();
            if (size > properties.maxBytes() - (queue == null ? 0 : queue.bytes)) throw new ProtocolException("room_queue_full");
            if (messages >= properties.globalMessages() || size > properties.globalBytes() - bytes) throw new ProtocolException("room_global_budget");
            if (queue == null) { queue = new RoomQueue(room); rooms.put(room, queue); }
            queue.entries.addLast(new Pending(message, clock.getAsLong()));
            queue.bytes += size; bytes += size; messages++;
            highWater = Math.max(highWater, bytes);
            if (!queue.scheduled) { queue.scheduled=true; ready.addLast(queue); monitor.notifyAll(); }
            return true;
        }
    }

    private void run() {
        try {
            while (true) {
                synchronized (monitor) {
                    while (!stopping && ready.isEmpty()) monitor.wait();
                    if (stopping) return;
                }
                dispatchOneTurn();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            synchronized (monitor) { stopping = true; cancelWaiting("shutdown"); }
        }
    }

    /**
     * 单消费者的一轮：只弹出一个房间，执行有限工作后放回队尾。
     * package-private 允许测试逐轮断言实际投递轨迹；运行时只有上面的一个 worker 调用。
     */
    boolean dispatchOneTurn() {
        RoomQueue queue;
        synchronized (monitor) {
            if (stopping || (queue = ready.pollFirst()) == null) return false;
            queue.running = true;
        }
        int delivered = 0;
        int completed = 0;
        try {
            while (completed < properties.batchMessages() && delivered < properties.batchRecipients()) {
                Pending pending;
                synchronized (monitor) {
                    if (stopping || queue.room.retired()) break;
                    pending = queue.entries.peekFirst();
                    if (pending == null) break;
                    if (pending.recipients == null && clock.getAsLong() - pending.queuedAt >= properties.maxAgeMillis() * 1_000_000L) {
                        removeFirst(queue, "expired");
                        completed++;
                        continue;
                    }
                }
                if (pending.recipients == null) {
                    // 在首次实际分发时取得快照。后续批次复用它，避免成员变化导致重复或漏遍历。
                    pending.recipients = registry.snapshot(queue.room);
                    metrics.counter("lab.deliveries.expected").increment(pending.recipients.size());
                    metrics.timer("lab.dispatch.wait").record(Math.max(0, clock.getAsLong() - pending.queuedAt), TimeUnit.NANOSECONDS);
                }
                while (pending.cursor < pending.recipients.size() && delivered < properties.batchRecipients()) {
                    synchronized (monitor) { if (stopping || queue.room.retired()) break; }
                    broadcaster.deliver(pending.recipients.get(pending.cursor), pending.message);
                    pending.cursor++;
                    delivered++;
                }
                synchronized (monitor) {
                    if (pending.cursor == pending.recipients.size()) {
                        removeFirst(queue, null);
                        metrics.counter("lab.dispatch.completed").increment();
                        completed++;
                    } else break;
                }
            }
        } catch (RuntimeException failure) {
            // 一个异常输入不能让共享线程永久退出。失败可观测，不能冒充完成分发。
            log.warn("Room dispatch failed for instance {}", queue.room.instanceId(), failure);
            synchronized (monitor) { if (!queue.entries.isEmpty()) removeFirst(queue, "dispatch_failure"); }
        } finally {
            synchronized (monitor) {
                queue.running = false;
                if (stopping || queue.room.retired()) {
                    while (!queue.entries.isEmpty()) removeFirst(queue, stopping ? "shutdown" : "room_closed");
                }
                if (queue.entries.isEmpty()) {
                    queue.scheduled = false;
                    rooms.remove(queue.room, queue);
                } else ready.addLast(queue);
            }
        }
        return true;
    }

    /** monitor 内归还一次预算；取消半途快照时另记尚未尝试的投递数，不能只报消息条数。 */
    private void removeFirst(RoomQueue queue, String reason) {
        var pending = queue.entries.removeFirst();
        int size = pending.message.getPayloadLength();
        queue.bytes -= size;
        bytes -= size;
        messages--;
        if (reason != null) {
            metrics.counter("lab.dispatch.discarded", "reason", reason).increment();
            if (pending.recipients != null) {
                metrics.counter("lab.deliveries.cancelled", "reason", reason)
                        .increment(pending.recipients.size() - pending.cursor);
            }
        }
    }

    /** 停机只清尚未开始的工作；正在运行的第一条仍占预算，由运行线程 finally 负责释放。 */
    private void cancelWaiting(String reason) {
        ready.clear();
        var iterator = rooms.values().iterator();
        while (iterator.hasNext()) {
            var queue = iterator.next();
            Pending inFlight = queue.running ? queue.entries.pollFirst() : null;
            while (!queue.entries.isEmpty()) removeFirst(queue, reason);
            if (inFlight != null) queue.entries.addFirst(inFlight);
            else { queue.scheduled = false; iterator.remove(); }
        }
    }

    public long bufferedBytes() { synchronized (monitor) { return bytes; } }
    public long bufferedMessages() { synchronized (monitor) { return messages; } }
    private long highWater() { synchronized (monitor) { return highWater; } }
    int roomCount() { synchronized (monitor) { return rooms.size(); } }
    int readyCount() { synchronized (monitor) { return ready.size(); } }

    /** 先停止准入并取消等待条目，再有限等待调度线程退出；不负责关闭输出层线程池。 */
    @PreDestroy
    public void shutdown() {
        synchronized (monitor) {
            stopping = true;
            cancelWaiting("shutdown");
            monitor.notifyAll();
        }
        if (worker != null) {
            worker.interrupt();
            try { worker.join(7000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    /** 一房间一个调度节点；scheduled 同时覆盖“就绪队列里”和“正在执行”，防止重复激活。 */
    private static final class RoomQueue {
        final Room room;
        final ArrayDeque<Pending> entries = new ArrayDeque<>();
        long bytes;
        boolean scheduled;
        boolean running;
        RoomQueue(Room room) { this.room = room; }
    }

    /** 只有单一调度线程修改 recipients/cursor；入队后消息不变，游标记录下一位待投递观众。 */
    private static final class Pending {
        final TextMessage message;
        final long queuedAt;
        List<ClientConnection> recipients;
        int cursor;
        Pending(TextMessage message, long queuedAt) { this.message = message; this.queuedAt = queuedAt; }
    }
}
