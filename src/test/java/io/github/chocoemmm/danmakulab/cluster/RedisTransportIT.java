package io.github.chocoemmm.danmakulab.cluster;

import io.github.chocoemmm.danmakulab.config.ClusterProperties;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 必须显式启用redis-integration并启动16379的真实Redis；不可连接时失败而非跳过。 */
class RedisTransportIT {
    @Test void twoRealSubscriptionsReceiveOnePublishAndSelfEcho() throws Exception {
        var a=new RedisPubSubTransport(properties("a")); var b=new RedisPubSubTransport(properties("b"));
        var first=new CopyOnWriteArrayList<String>(); var second=new CopyOnWriteArrayList<String>();
        try {
            assertThatThrownBy(() -> a.publish("before-subscribe")).isInstanceOf(ClusterTransport.NotAttempted.class);
            a.start(first::add); b.start(second::add);
            await().atMost(10,TimeUnit.SECONDS).until(() -> a.ready() && b.ready());
            assertThat(a.publish("one-message")).isEqualTo(2);
            await().atMost(3,TimeUnit.SECONDS).until(() -> first.size()==1 && second.size()==1);
            assertThat(first).containsExactly("one-message"); assertThat(second).containsExactly("one-message");
        } finally { a.close(); b.close(); }
        assertThat(a.ready()).isFalse(); assertThat(b.ready()).isFalse();
    }
    private ClusterProperties properties(String node) {
        // 独立频道避免和正在运行的演示节点串数据，不能借它们的订阅数量美化结果。
        return new ClusterProperties("redis",node,"transport-it","redis://127.0.0.1:16379",4096,256,1048576,1000,1000);
    }
}
