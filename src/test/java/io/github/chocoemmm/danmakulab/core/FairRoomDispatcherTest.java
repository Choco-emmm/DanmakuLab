package io.github.chocoemmm.danmakulab.core;

import io.github.chocoemmm.danmakulab.config.DispatchProperties;
import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.config.OutboundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 关闭自动线程、逐轮驱动真实调度器，断言投递轨迹而不是只检查调用次数。
 * 网络层用 mock 隔离；另有 FairWebSocketIntegrationTest 验证真实客户端接收。
 */
class FairRoomDispatcherTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ConnectionRegistry registry = mock(ConnectionRegistry.class);
    private final DirectBroadcaster broadcaster = mock(DirectBroadcaster.class);
    private final ClientConnection sender = viewer("sender");
    private final AtomicLong clock = new AtomicLong();
    private final HashMap<Room, List<ClientConnection>> audiences = new HashMap<>();
    private final List<String> trace = new CopyOnWriteArrayList<>();
    private FairRoomDispatcher dispatcher;

    @BeforeEach
    void configureFakeTransport() throws Exception {
        when(broadcaster.prepare(any(), any(), anyString(), anyString(), anyLong(), anyLong()))
                .thenAnswer(call -> new TextMessage(call.getArgument(5) + ":" + call.getArgument(3)));
        when(registry.snapshot(any())).thenAnswer(call -> audiences.getOrDefault(call.getArgument(0), List.of(sender)));
        doAnswer(call -> {
            ClientConnection recipient = call.getArgument(0);
            TextMessage message = call.getArgument(1);
            trace.add(recipient.id() + "/" + message.getPayload());
            return null;
        }).when(broadcaster).deliver(any(), any());
    }

    @AfterEach
    void cleanup() {
        if (dispatcher != null) dispatcher.shutdown();
        metrics.close();
    }

    /** 远端事件保留来源序号且不回复发送者；仅占本地预算，不能调用prepare或再次生成accepted。 */
    @Test
    void remoteBroadcastPreservesPayloadAndDoesNotAllocateLocalSequence() throws Exception {
        create(2,1000,3,2000);
        var room=new Room("remote",50);
        when(registry.currentRoom("remote")).thenReturn(room);
        audiences.put(room,List.of(viewer("viewer")));
        assertThat(dispatcher.acceptBroadcast("remote",new TextMessage("origin-seq-88"))).isTrue();
        assertThat(room.sequenceCandidate()).isEqualTo(1);
        drain();
        assertThat(trace).containsExactly("viewer/origin-seq-88");
        assertThat(metrics.find("lab.messages.accepted").counter()).isNull();
        verify(broadcaster,never()).prepare(any(),any(),anyString(),anyString(),anyLong(),anyLong());
        assertEmpty();
    }

    /** 没有本地观众/旧房间已退休时忽略；远端队列满仍明确拒绝并保持既有预算不变。 */
    @Test
    void remoteBroadcastUsesLocalCapacityAndRoomLifetime() {
        create(1,1000,2,2000);
        assertThat(dispatcher.acceptBroadcast("missing",new TextMessage("x"))).isFalse();
        var room=new Room("remote",50);
        when(registry.currentRoom("remote")).thenReturn(room);
        assertThat(dispatcher.acceptBroadcast("remote",new TextMessage("one"))).isTrue();
        assertThatThrownBy(() -> dispatcher.acceptBroadcast("remote",new TextMessage("two"))).hasMessage("room_queue_full");
        assertThat(dispatcher.bufferedBytes()).isEqualTo(3);
        room.retire();
        assertThat(dispatcher.acceptBroadcast("remote",new TextMessage("late"))).isFalse();
        drain(); assertEmpty();
    }

    /** 热点的五人广播跨三轮；普通房间能在热点两次轮转之间执行，同房间后一条不能插队。 */
    @Test
    void roundRobinBoundsFanoutAndPreservesPerViewerOrder() throws Exception {
        create(10, 1000, 20, 2000);
        var hot = new Room("hot", 50);
        var normal = new Room("normal", 50);
        audiences.put(hot, List.of(viewer("h0"), viewer("h1"), viewer("h2"), viewer("h3"), viewer("h4")));
        audiences.put(normal, List.of(viewer("n")));
        submit(hot, "hot-1"); submit(hot, "hot-2"); submit(normal, "normal");
        dispatcher.dispatchOneTurn();
        assertThat(trace).containsExactly("h0/1:hot-1", "h1/1:hot-1");
        dispatcher.dispatchOneTurn();
        assertThat(trace.get(2)).isEqualTo("n/1:normal");
        drain();
        for (int i = 0; i < 5; i++) {
            final String prefix = "h" + i + "/";
            assertThat(trace.stream().filter(line -> line.startsWith(prefix)).toList())
                    .containsExactly(prefix + "1:hot-1", prefix + "2:hot-2");
        }
        assertEmpty();
    }

    /** 第三条房间输入被拒绝，另一房间触及全局条数也被拒绝；这些请求都不能占用序号。 */
    @Test
    void roomAndGlobalMessageCapsRejectWithoutConsumingSequence() throws Exception {
        create(2, 1000, 3, 2000);
        var a = new Room("a", 50); var b = new Room("b", 50);
        submit(a, "one"); submit(a, "two");
        assertThatThrownBy(() -> submit(a, "rejected")).hasMessage("room_queue_full");
        assertThat(a.sequenceCandidate()).isEqualTo(3);
        submit(b, "one");
        assertThatThrownBy(() -> submit(b, "rejected")).hasMessage("room_global_budget");
        assertThat(b.sequenceCandidate()).isEqualTo(2);
        assertThat(dispatcher.bufferedMessages()).isEqualTo(3);
        drain(); submit(a, "three"); drain();
        assertThat(trace).contains("sender/3:three");
        assertThat(metrics.counter("lab.messages.accepted").count()).isEqualTo(4);
        assertEmpty();
    }

    /** 按完整编码消息的字节拒绝；条数没满，也不能越过每房间字节上限。 */
    @Test
    void roomByteBudgetRejectsBeforeCommit() throws Exception {
        create(10, 5, 20, 100);
        var room = new Room("bytes", 50);
        submit(room, "ab"); // 模拟广播为 1:ab，4 个 UTF-8 字节。
        assertThatThrownBy(() -> submit(room, "cd")).hasMessage("room_queue_full");
        assertThat(dispatcher.bufferedBytes()).isEqualTo(4);
        assertThat(room.sequenceCandidate()).isEqualTo(2);
        drain(); assertEmpty();
    }

    /** 不同房间共享字节预算；不能通过多建房间绕过总内存边界。 */
    @Test
    void globalByteBudgetIncludesAllRooms() throws Exception {
        create(10, 100, 20, 7);
        submit(new Room("a", 50), "ab");
        assertThatThrownBy(() -> submit(new Room("b", 50), "cd")).hasMessage("room_global_budget");
        assertThat(dispatcher.roomCount()).isEqualTo(1);
        drain(); assertEmpty();
    }

    /** UTF-8 的汉字占三个字节，不能按 Java String.length() 把五字节广播误算成三字节。 */
    @Test
    void utf8BytesRatherThanCharacterCountControlBudget() {
        create(10, 4, 20, 100);
        assertThatThrownBy(() -> submit(new Room("utf8", 50), "汉")).hasMessage("room_queue_full");
        assertEmpty();
    }

    /** 就绪队列本身也必须有界：十个待处理旧实例占满名额，回收后新实例才可进入。 */
    @Test
    void schedulerRoomSlotsStayBoundedAcrossRoomChurn() throws Exception {
        create(10, 1000, 20, 20000);
        var oldRooms = new ArrayList<Room>();
        for (int i = 0; i < 10; i++) {
            var room = new Room("room-" + i, 50);
            oldRooms.add(room); submit(room, "old");
        }
        var next = new Room("replacement", 50);
        assertThatThrownBy(() -> submit(next, "new")).hasMessage("room_scheduler_full");
        assertThat(dispatcher.readyCount()).isEqualTo(10);
        oldRooms.forEach(Room::retire);
        drain(); submit(next, "new"); drain();
        assertThat(trace).containsExactly("sender/1:new");
        assertEmpty();
    }

    /** 第一条跨批次的消息只取一次快照；下一条才使用新的成员列表，不在半途重新扫描。 */
    @Test
    void fanoutSnapshotIsStableAcrossTurns() throws Exception {
        create(10, 1000, 20, 2000);
        var room = new Room("snapshot", 50);
        var a = viewer("a"); var b = viewer("b"); var c = viewer("c"); var d = viewer("d");
        audiences.put(room, List.of(a, b, c));
        submit(room, "first"); submit(room, "second");
        dispatcher.dispatchOneTurn();
        audiences.put(room, List.of(a, d));
        drain();
        assertThat(trace).containsExactly("a/1:first", "b/1:first", "c/1:first", "a/2:second", "d/2:second");
        assertEmpty();
    }

    /** 房间在一次大广播中途退休，未尝试的投递单独计数，不能泄漏预算或继续发送旧实例。 */
    @Test
    void retirementCancelsRemainingFanout() throws Exception {
        create(10, 1000, 20, 2000);
        var room = new Room("retired", 50);
        audiences.put(room, List.of(viewer("a"), viewer("b"), viewer("c")));
        submit(room, "first"); submit(room, "second");
        dispatcher.dispatchOneTurn();
        room.retire(); drain();
        assertThat(trace).containsExactly("a/1:first", "b/1:first");
        assertThat(metrics.counter("lab.deliveries.cancelled", "reason", "room_closed").count()).isEqualTo(1);
        assertThat(metrics.counter("lab.dispatch.discarded", "reason", "room_closed").count()).isEqualTo(2);
        assertEmpty();
    }

    /** 并发八十次入队，观众最终看到严格递增的序号，不能靠每个发送者自己的顺序蒙混过关。 */
    @Test
    void concurrentAdmissionsHaveOneSharedRoomOrder() throws Exception {
        create(80, 10000, 100, 20000);
        var room = new Room("concurrent", 1000);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var work = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 80; i++) {
                final String id = "input-" + i;
                work.add(executor.submit(() -> { submit(room, id); return null; }));
            }
            for (var task : work) task.get(3, TimeUnit.SECONDS);
        }
        drain();
        assertThat(trace).hasSize(80);
        for (int i = 0; i < 80; i++) assertThat(trace.get(i)).startsWith("sender/" + (i + 1) + ":");
        assertEmpty();
    }

    /** 同名新实例不能收到旧队列内容；旧房间接受过的输入被明确计为取消，而不是重新路由。 */
    @Test
    void retiredRoomDoesNotLeakIntoRecreatedRoom() throws Exception {
        create(10, 1000, 20, 2000);
        var old = new Room("same", 50);
        submit(old, "old"); old.retire();
        var replacement = new Room("same", 50);
        submit(replacement, "new");
        assertThatThrownBy(() -> submit(old, "late")).hasMessage("room_closed");
        drain();
        assertThat(trace).containsExactly("sender/1:new");
        assertThat(metrics.counter("lab.dispatch.discarded", "reason", "room_closed").count()).isEqualTo(1);
        assertEmpty();
    }

    /** 假时钟精确跨过 TTL；已接受但未开始的输入可过期，后续输入保留真实序号缺口。 */
    @Test
    void expiryReleasesBudgetAndLeavesSequenceGap() throws Exception {
        create(10, 1000, 20, 2000);
        var room = new Room("expiry", 50);
        submit(room, "old");
        clock.set(TimeUnit.SECONDS.toNanos(1));
        drain();
        submit(room, "new"); drain();
        assertThat(trace).containsExactly("sender/2:new");
        assertThat(metrics.counter("lab.dispatch.discarded", "reason", "expired").count()).isEqualTo(1);
        assertEmpty();
    }

    /** 异常分发必须释放预算、记录未尝试投递，并继续服务另一个房间。 */
    @Test
    void unexpectedFailureDoesNotStrandOtherRooms() throws Exception {
        create(10, 1000, 20, 2000);
        var broken = viewer("broken");
        var room = new Room("broken", 50);
        audiences.put(room, List.of(broken));
        doThrow(new IllegalStateException("synthetic failure")).when(broadcaster).deliver(eq(broken), any());
        submit(room, "bad"); submit(new Room("normal", 50), "good");
        drain();
        assertThat(trace).containsExactly("sender/1:good");
        assertThat(metrics.counter("lab.dispatch.discarded", "reason", "dispatch_failure").count()).isEqualTo(1);
        assertThat(metrics.counter("lab.deliveries.cancelled", "reason", "dispatch_failure").count()).isEqualTo(1);
        assertEmpty();
    }

    /** 停机与投递交错：等待条目立即取消，在途第一条在返回之前仍占预算，之后只能释放一次。 */
    @Test
    void shutdownKeepsInFlightBudgetUntilTurnReturns() throws Exception {
        create(10, 1000, 20, 2000);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(broadcaster).deliver(any(), any());
        var room = new Room("a", 50);
        audiences.put(room, List.of(viewer("a"), viewer("b"), viewer("c")));
        submit(room, "in-flight"); submit(room, "waiting"); submit(new Room("b", 50), "waiting");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var turn = executor.submit(() -> dispatcher.dispatchOneTurn());
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                dispatcher.shutdown();
                assertThat(dispatcher.bufferedMessages()).isEqualTo(1);
                assertThat(dispatcher.bufferedBytes()).isGreaterThan(0);
                assertThatThrownBy(() -> submit(room, "late")).hasMessage("server_stopping");
            } finally { release.countDown(); }
            turn.get(3, TimeUnit.SECONDS);
        }
        assertThat(metrics.counter("lab.dispatch.discarded", "reason", "shutdown").count()).isEqualTo(3);
        assertThat(metrics.counter("lab.deliveries.cancelled", "reason", "shutdown").count()).isEqualTo(2);
        assertEmpty();
    }

    /** 禁止把直接网络写接到共享房间线程上，否则一个慢连接会堵住所有房间。 */
    @Test
    void fairModeRequiresBufferedOutput() {
        assertThatThrownBy(() -> new FairRoomDispatcher(properties(10, 1000, 20, 2000), lab(),
                output("direct"), registry, broadcaster, metrics, clock::get, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires");
    }

    private void create(int count, long bytes, int globalCount, long globalBytes) {
        dispatcher = new FairRoomDispatcher(properties(count, bytes, globalCount, globalBytes), lab(),
                output("buffered"), registry, broadcaster, metrics, clock::get, false);
    }

    private static DispatchProperties properties(int count, long bytes, int globalCount, long globalBytes) {
        return new DispatchProperties("fair", count, bytes, globalCount, globalBytes, 4, 2, 1000);
    }

    private static LabProperties lab() { return new LabProperties(2000, 10, 1024, 200, 5, 50); }
    private static OutboundProperties output(String mode) { return new OutboundProperties(mode, 256, 65536, 134217728, 8, 2000, 1000, 16); }
    private static ClientConnection viewer(String id) {
        var connection = mock(ClientConnection.class);
        when(connection.id()).thenReturn(id);
        return connection;
    }

    private void submit(Room room, String text) throws Exception {
        dispatcher.submit(sender, new ConnectionRegistry.Membership(room, "name"), text, text, 0);
    }

    /** 有限执行直至无工作，防止错误的重复激活让单元测试永远挂住。 */
    private void drain() {
        int turns = 0;
        while (dispatcher.dispatchOneTurn()) assertThat(++turns).isLessThan(200);
    }

    private void assertEmpty() {
        assertThat(dispatcher.bufferedBytes()).isZero();
        assertThat(dispatcher.bufferedMessages()).isZero();
        assertThat(dispatcher.roomCount()).isZero();
        assertThat(dispatcher.readyCount()).isZero();
    }
}
