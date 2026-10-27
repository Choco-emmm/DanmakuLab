package io.github.chocoemmm.danmakulab.cluster;

import io.github.chocoemmm.danmakulab.config.ClusterProperties;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 真正Redis通信边界。发布连接禁止自动重连/旧命令重放；订阅连接允许自动重连和恢复订阅。
 * 两者共享小规模Netty资源，避免每条WebSocket都分配Redis连接或线程。
 * ready仅在收到SUBSCRIBE确认后为真，TCP重连成功本身不是订阅就绪。
 */
@Component
@ConditionalOnProperty(name="lab.cluster.mode", havingValue="redis")
final class RedisPubSubTransport implements ClusterTransport {
    private final ClusterProperties properties;
    private final DefaultClientResources resources;
    private final RedisClient publisherClient;
    private final RedisClient subscriberClient;
    // connector线程完成首次SUBSCRIBE后发布连接引用；停机线程读取，因此用volatile保证可见性。
    private volatile StatefulRedisPubSubConnection<String,String> subscription;
    // 运行期间只有唯一发布线程创建、使用和废弃它；关闭在入口等待发布线程退出后执行。
    private StatefulRedisConnection<String,String> publisher;
    // Lettuce网络回调更新订阅确认状态，入口/发布线程读取；TCP已连接并不代表已订阅。
    private volatile boolean subscribed;
    // 停机线程写，首次建连线程和网络回调读，阻止退出期间恢复ready或继续接收。
    private volatile boolean stopping;
    // start创建一次，close中断并有限等待它退出；不为每次重连创建新线程。
    private Thread connector;

    RedisPubSubTransport(ClusterProperties properties) {
        this.properties=properties;
        var uri=RedisURI.create(properties.redisUri());
        uri.setTimeout(Duration.ofMillis(properties.timeoutMillis()));
        resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        publisherClient=RedisClient.create(resources,uri);
        subscriberClient=RedisClient.create(resources,uri);
        var socket=SocketOptions.builder().connectTimeout(Duration.ofMillis(properties.timeoutMillis())).build();
        publisherClient.setOptions(ClientOptions.builder().autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(1).socketOptions(socket).build());
        subscriberClient.setOptions(ClientOptions.builder().autoReconnect(true).requestQueueSize(8)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).socketOptions(socket).build());
        subscriberClient.addListener(new RedisConnectionStateListener() {
            @Override public void onRedisDisconnected(RedisChannelHandler<?,?> connection) { subscribed=false; }
        });
    }

    @Override public void start(Consumer<String> receiver) {
        connector=new Thread(() -> {
            while (!stopping) {
                // 这里只重试首次建连/订阅失败。成功保存subscription后，后续断线重连及恢复订阅
                // 交给Lettuce；收到subscribed回调才重新ready，不把TCP重连当成订阅已确认。
                if (subscription == null) {
                    StatefulRedisPubSubConnection<String,String> candidate=null;
                    try {
                        candidate=subscriberClient.connectPubSub();
                        candidate.addListener(new RedisPubSubAdapter<>() {
                            @Override public void message(String channel,String value) {
                                if (!stopping && channel.equals(properties.channel())) receiver.accept(value);
                            }
                            @Override public void subscribed(String channel,long count) {
                                if (!stopping && channel.equals(properties.channel())) subscribed=true;
                            }
                        });
                        candidate.sync().subscribe(properties.channel());
                        subscription=candidate;
                    } catch (Exception unavailable) {
                        // 首次握手未完成的候选连接不能保留；下一轮重新创建，不缓存断线期间消息。
                        subscribed=false;
                        if (candidate != null) candidate.close();
                    }
                }
                try { Thread.sleep(500); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        },"lab-redis-subscribe");
        connector.setDaemon(true); connector.start();
    }

    @Override public boolean ready() { return subscribed && !stopping; }

    /** 只由发布线程调用；异常后丢弃连接，不重试同一PUBLISH。建连失败可确定尚未发布。 */
    @Override public long publish(String value) throws Exception {
        if (!ready()) throw new NotAttempted("bus_unavailable");
        if (publisher == null || !publisher.isOpen()) {
            try { publisher=publisherClient.connect(); }
            catch (Exception unavailable) { throw new NotAttempted("bus_unavailable"); }
        }
        try { return publisher.sync().publish(properties.channel(),value); }
        catch (Exception uncertain) {
            publisher.close(); publisher=null;
            throw uncertain;
        }
    }

    /** 先让订阅回调停止入队；发布线程的单次等待有超时，退出后调用本方法销毁网络资源。 */
    @Override public synchronized void close() {
        if (stopping) return;
        stopping=true; subscribed=false;
        if (connector != null) {
            connector.interrupt();
            try { connector.join(properties.timeoutMillis()+1500L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (subscription != null) subscription.close();
        if (publisher != null) publisher.close();
        publisherClient.shutdown(Duration.ZERO,Duration.ofSeconds(2));
        subscriberClient.shutdown(Duration.ZERO,Duration.ofSeconds(2));
        try { resources.shutdown(0,2,TimeUnit.SECONDS).get(3,TimeUnit.SECONDS); }
        catch (Exception ignored) { /* 关闭已尽力完成；不让后续组件预算清理被关闭异常跳过。 */ }
    }
}
