package io.github.chocoemmm.danmakulab.core;

import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.protocol.ProtocolException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只验证内存中的连接/成员关系，用模拟 Session 隔离真实网络，便于准确触发容量边界。 */
class ConnectionRegistryTest {
    // 故意将容量设得很小：最多 2 条连接、1 个房间，少量对象即可触发限制。
    private final ConnectionRegistry registry = new ConnectionRegistry(
            new LabProperties(2, 1, 1024, 200, 5, 50), new SimpleMeterRegistry());

    /** 达到连接上限后拒绝新连接；重复移除同一连接安全，且回收后可以重新接纳。 */
    @Test
    void enforcesConnectionCapAndReclaimsAfterIdempotentRemoval() {
        registry.register(session("a"));
        registry.register(session("b"));
        assertThatThrownBy(() -> registry.register(session("c")))
                .isInstanceOf(ProtocolException.class).hasMessage("connection_limit");
        registry.remove("a");
        registry.remove("a");
        registry.register(session("c"));
        assertThat(registry.connectionCount()).isEqualTo(2);
    }

    /** 换房失败保留旧成员；旧房间只剩自己时可以释放旧房间名额并创建新房间。 */
    @Test
    void failedRoomSwitchPreservesMembershipAndEmptyRoomsAreReclaimed() {
        var a = registry.register(session("a"));
        var b = registry.register(session("b"));
        var first = registry.join(a, "one", "A");
        registry.join(b, "one", "B");
        assertThatThrownBy(() -> registry.join(a, "two", "A"))
                .hasMessage("room_limit");
        assertThat(registry.membership(a)).isEqualTo(first);
        registry.remove("b");
        var second = registry.join(a, "two", "A");
        assertThat(registry.snapshot(first.room())).isEmpty();
        assertThat(registry.snapshot(second.room())).containsExactly(a);
        registry.remove("a");
        assertThat(registry.roomCount()).isZero();
    }

    /** 同房间重复 join 只改昵称，Room 对象不变，序号与限流器也就不会被重建。 */
    @Test
    void repeatedJoinPreservesRoomSequenceAndLimiter() {
        var a = registry.register(session("a"));
        var original = registry.join(a, "one", "A");
        var repeated = registry.join(a, "one", "new name");
        assertThat(repeated.room()).isSameAs(original.room());
        assertThat(repeated.nickname()).isEqualTo("new name");
    }

    /** Mockito 的 mock 只返回指定 ID；本测试不涉及发送，因此无需创建真实连接。 */
    private WebSocketSession session(String id) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
