package io.github.chocoemmm.danmakulab.cluster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 有界FIFO（先进先出）。take移除排队节点但不归还预算，工作完成后Slot.close才归还。
 * 例如容量2时，一条正在发布、一条等待，第三条仍被拒绝，避免“出队就不算内存”的遗漏。
 * 本类锁只保护队列与计数；调用者必须在锁外执行网络、解析与反馈。stop仅取消等待项。
 */
final class BudgetQueue<T> {
    private final int maxCount;
    private final long maxBytes;
    private final ArrayDeque<Slot> waiting = new ArrayDeque<>();
    private int count;
    private long bytes;
    private boolean stopped;

    BudgetQueue(int maxCount, long maxBytes) { this.maxCount=maxCount; this.maxBytes=maxBytes; }

    synchronized boolean offer(T value, int size) {
        if (size < 0 || stopped || count >= maxCount || size > maxBytes-bytes) return false;
        waiting.addLast(new Slot(value,size)); count++; bytes+=size; notifyAll(); return true;
    }

    synchronized Slot take() throws InterruptedException {
        while (!stopped && waiting.isEmpty()) wait();
        return waiting.pollFirst();
    }

    synchronized List<T> stop() {
        stopped=true;
        var cancelled=new ArrayList<T>();
        while (!waiting.isEmpty()) {
            var slot=waiting.removeFirst(); cancelled.add(slot.value); slot.close();
        }
        notifyAll(); return cancelled;
    }
    synchronized int count() { return count; }
    synchronized long bytes() { return bytes; }

    /** close允许重复调用，即幂等；正常、异常、停机相遇时不会重复扣减预算。 */
    final class Slot implements AutoCloseable {
        final T value;
        private final int size;
        private final AtomicBoolean released=new AtomicBoolean();
        Slot(T value,int size) { this.value=value; this.size=size; }
        @Override public void close() {
            synchronized (BudgetQueue.this) {
                if (released.compareAndSet(false,true)) { count--; bytes-=size; }
            }
        }
    }
}
