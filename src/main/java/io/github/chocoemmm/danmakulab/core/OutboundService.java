package io.github.chocoemmm.danmakulab.core;

import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.config.OutboundProperties;
import io.github.chocoemmm.danmakulab.protocol.ProtocolException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 连接邮箱共享的资源所有者：全局预算、有界发送池、独立关闭池以及定时过期检查。
 * 不依赖 Registry；连接失效时调用登记时传入的注销回调，避免 Spring 组件循环依赖。
 * active 包含仍在异步关闭中的连接。正常运行时，关闭任务完成之前不归还连接名额，因此关闭池
 * 队列最多接纳 maxConnections 个一次性任务。仅强制停机时放弃未启动的关闭任务并归还名额，
 * 底层传输的最终销毁交给容器退出；该阶段已停止准入，归还不会被新连接重复占用。
 */
@Component
public class OutboundService {
    final OutboundProperties properties;
    final MeterRegistry metrics;
    private final int connectionLimit;
    private final Set<ClientConnection> active = ConcurrentHashMap.newKeySet();
    private final AtomicLong slots = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong messages = new AtomicLong();
    private final AtomicLong highWater = new AtomicLong();
    private final ThreadPoolExecutor writers;
    private final ThreadPoolExecutor closers;
    private final ScheduledExecutorService maintenance;
    private volatile boolean stopping;

    /**
     * Spring 创建本组件时按参数类型注入依赖：两个配置对象由配置扫描绑定，
     * MeterRegistry 由 Actuator 自动配置提供。只有一个构造器，所以无需额外写 Autowired。
     * 本构造器只做一次初始化；内部线程池由本类持有，并由 PreDestroy 方法负责关闭。
     */
    public OutboundService(OutboundProperties properties, LabProperties lab, MeterRegistry metrics) {
        // 保存依赖与连接容量，后续入队和失败关闭共用这些配置。
        this.properties = properties;
        this.metrics = metrics;
        this.connectionLimit = lab.maxConnections();
        // 为所有连接共享固定发送池和独立关闭池；任务排队有上限，不按连接创建线程。
        writers = pool(properties.workers(), properties.taskCapacity(), "lab-send-");
        closers = pool(2, connectionLimit, "lab-close-");
        maintenance = Executors.newSingleThreadScheduledExecutor(factory("lab-expiry-"));
        // Gauge 是“当前值仪表”：登记 AtomicLong 或读取函数，不替业务代码改变计数。
        // 例如预留/释放预算会改变 bytes，查询 lab.outbound.bytes 时读到它的当前值。
        metrics.gauge("lab.outbound.bytes", bytes);
        metrics.gauge("lab.outbound.messages", messages);
        metrics.gauge("lab.outbound.bytes.highwater", highWater);
        metrics.gauge("lab.outbound.slots", slots);
        metrics.gauge("lab.outbound.tasks", writers, executor -> executor.getQueue().size());
        metrics.gauge("lab.outbound.writers.active", writers, ThreadPoolExecutor::getActiveCount);
        metrics.gauge("lab.outbound.mode.buffered", properties.buffered() ? 1 : 0);
        if (properties.buffered()) {
            // 只有一个周期任务，不为每条消息创建定时器。快照遍历的连接最多为连接上限。
            maintenance.scheduleWithFixedDelay(() -> active.forEach(ClientConnection::expirePending),
                    100, 100, TimeUnit.MILLISECONDS);
        }
    }

    private static ThreadFactory factory(String prefix) {
        var index = new AtomicLong();
        return task -> {
            var thread = new Thread(task, prefix + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static ThreadPoolExecutor pool(int size, int capacity, String name) {
        // AbortPolicy 抛出拒绝异常；不能 CallerRuns，把慢网络发送转移回广播线程。
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), factory(name), new ThreadPoolExecutor.AbortPolicy());
    }

    /** 登记一条连接，同时占用关闭完成前不可复用的名额；直发模式不启用邮箱。 */
    synchronized ClientConnection connection(WebSocketSession session, int inputRate, Runnable invalidate) {
        if (!properties.buffered()) return new ClientConnection(session, inputRate);
        if (stopping) throw new ProtocolException("connection_limit");
        if (slots.incrementAndGet() > connectionLimit) {
            slots.decrementAndGet();
            throw new ProtocolException("connection_limit");
        }
        var connection = new ClientConnection(session, inputRate, this, invalidate);
        active.add(connection);
        return connection;
    }

    /** CAS（比较并交换）只在余额未变化时扣预算，避免多个邮箱同时越过全局字节上限。 */
    boolean reserve(long size) {
        while (true) {
            long current = bytes.get();
            if (size > properties.globalBytes() - current) return false;
            if (bytes.compareAndSet(current, current + size)) {
                messages.incrementAndGet();
                highWater.accumulateAndGet(current + size, Math::max);
                return true;
            }
        }
    }

    void release(long size) {
        bytes.addAndGet(-size);
        messages.decrementAndGet();
    }

    void schedule(Runnable task) {
        writers.execute(task);
    }

    void closeLater(ClientConnection connection, Runnable close) {
        // 每条仍占名额的连接最多提交一次关闭任务，队列容量就是名额上限。
        // 关闭也可能等待容器，必须与广播线程和发送池隔离。
        closers.execute(new CloseTask(connection, close));
    }

    /**
     * 显式保存任务所属连接：shutdownNow 返回的排队任务不会执行 run，也就不会进入 finally。
     * 因而必须有独立的 abandon 路径归还名额。claimed 让“执行”与“取消”只能有一个成功，
     * 即使重复停机或未来执行器实现改变，也不能重复关闭或重复处理同一名额。
     */
    private final class CloseTask implements Runnable {
        private final ClientConnection connection;
        private final Runnable close;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private CloseTask(ClientConnection connection, Runnable close) {
            this.connection = connection;
            this.close = close;
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            try { close.run(); }
            finally { retire(connection); }
        }

        /** 只取消未开始的关闭任务；此方法绝不在停机线程里调用可能阻塞的 Session.close。 */
        private void abandon() {
            if (!claimed.compareAndSet(false, true)) return;
            try { metrics.counter("lab.outbound.close.abandoned").increment(); }
            finally { retire(connection); }
        }
    }

    /**
     * shutdownNow 只中断正在运行的关闭者，并返回没有开始的任务；逐个取消后者的资源归属。
     * 已在运行的任务仍由自身 finally 归还名额；在途消息也仍由发送者 finally 释放预算。
     */
    private void stopClosersNow() {
        for (var task : closers.shutdownNow()) {
            if (task instanceof CloseTask closeTask) closeTask.abandon();
        }
    }

    void retire(ClientConnection connection) {
        if (active.remove(connection)) slots.decrementAndGet();
    }

    void event(String metric, String reason) {
        metrics.counter(metric, "reason", reason).increment();
    }

    public long bufferedBytes() { return bytes.get(); }
    public long bufferedMessages() { return messages.get(); }

    /** 先停止新入队并使连接失效，再等发送/关闭池收尾；没有无界等待应用退出。 */
    @PreDestroy
    public void shutdown() {
        synchronized (this) { stopping = true; }
        maintenance.shutdownNow();
        active.forEach(connection -> connection.fail("shutdown"));
        writers.shutdown();
        closers.shutdown();
        try {
            if (!writers.awaitTermination(7, TimeUnit.SECONDS)) writers.shutdownNow();
            if (!closers.awaitTermination(7, TimeUnit.SECONDS)) stopClosersNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writers.shutdownNow();
            stopClosersNow();
        }
    }
}
