package io.github.chocoemmm.danmakulab.cluster;

import java.util.function.Consumer;

/** 可替换的网络边界；测试可注入“执行了但回复丢失”等结果，不把模拟误当真实Redis集成。 */
interface ClusterTransport extends AutoCloseable {
    void start(Consumer<String> receiver);
    boolean ready();
    long publish(String value) throws Exception;
    @Override void close();

    /** 此异常仅用于确定尚未调用PUBLISH的失败，其余异常一律归为结果未知。 */
    final class NotAttempted extends Exception {
        NotAttempted(String message) { super(message); }
    }
}
