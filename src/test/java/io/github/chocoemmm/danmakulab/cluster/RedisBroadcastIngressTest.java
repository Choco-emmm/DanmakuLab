package io.github.chocoemmm.danmakulab.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chocoemmm.danmakulab.config.*;
import io.github.chocoemmm.danmakulab.core.*;
import io.github.chocoemmm.danmakulab.protocol.ProtocolException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.web.socket.TextMessage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 替身只模拟Redis网络边界；真实两节点/断网另由运行脚本验证，不把这些测试称为网络实测。 */
class RedisBroadcastIngressTest {
    private final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    private final FairRoomDispatcher dispatcher=mock(FairRoomDispatcher.class);
    private final DirectBroadcaster broadcaster=mock(DirectBroadcaster.class);
    private final ClientConnection sender=mock(ClientConnection.class);
    private final FakeTransport transport=new FakeTransport();
    private final Room room=new Room("lobby",50);
    private final ConnectionRegistry.Membership member=new ConnectionRegistry.Membership(room,"测试者");
    private final ObjectMapper json=new ObjectMapper();
    private final List<Map<String,?>> responses=new CopyOnWriteArrayList<>();
    private RedisBroadcastIngress ingress;

    @BeforeEach void setup() throws Exception {
        when(dispatcher.enabled()).thenReturn(true);
        when(dispatcher.acceptBroadcast(anyString(),any())).thenReturn(true);
        when(broadcaster.prepare(any(),any(),anyString(),anyString(),anyLong(),anyLong())).thenAnswer(call -> {
            var event=new LinkedHashMap<String,Object>();
            event.put("type","danmaku"); event.put("runId","run"); event.put("roomId","lobby");
            event.put("roomInstanceId",room.instanceId()); event.put("roomSeq",call.getArgument(5));
            event.put("clientMessageId",call.getArgument(2)); event.put("text",call.getArgument(3));
            event.put("clientSentAt",call.getArgument(4)); event.put("senderId","s"); event.put("nickname","测试者");
            return new TextMessage(json.writeValueAsString(event));
        });
        doAnswer(call -> { responses.add(call.getArgument(1)); return true; }).when(broadcaster).reply(eq(sender),anyMap());
    }
    private ClusterProperties properties(int count,int age) {
        return new ClusterProperties("redis","node-a","test","redis://127.0.0.1:16379",4096,count,1048576,age,1000);
    }
    private OutboundProperties outbound() { return new OutboundProperties("buffered",256,65536,134217728,8,2000,1000,16); }
    private void create(int count,int age) {
        ingress=new RedisBroadcastIngress(properties(count,age),outbound(),dispatcher,broadcaster,json,metrics,transport);
    }
    private void send(String id) throws Exception { ingress.submit(sender,member,id,"你好",10); }
    private void waitResponses(int count) { await().atMost(4,TimeUnit.SECONDS).until(() -> responses.size()==count); }
    @AfterEach void cleanup() { transport.release.countDown(); if(ingress!=null) ingress.shutdown(); metrics.close(); }

    /** take后预算仍保留，重复close和停机清理不重复扣减；验证条数、字节与在途边界。 */
    @Test void queueBudgetIncludesInFlightAndCancellationIsIdempotent() throws Exception {
        var q=new BudgetQueue<String>(2,5);
        assertThat(q.offer("one",3)).isTrue();
        var first=q.take();
        assertThat(q.offer("too-big",3)).isFalse();
        assertThat(q.offer("two",2)).isTrue();
        assertThat(q.offer("three",0)).isFalse();
        assertThat(q.stop()).containsExactly("two");
        assertThat(q.count()).isEqualTo(1); assertThat(q.bytes()).isEqualTo(3);
        first.close(); first.close();
        assertThat(q.count()).isZero(); assertThat(q.bytes()).isZero();
        assertThat(q.offer("late",1)).isFalse(); assertThat(q.take()).isNull();
    }

    /** accepted必须等待发布回复；本地投递只能来自订阅回流，来源身份保留而不再次发布。 */
    @Test void onlyEchoDeliversAndPublishAcknowledgementDefinesAccepted() throws Exception {
        transport.block=true; create(2,1000); send("first");
        assertThat(transport.entered.await(2,TimeUnit.SECONDS)).isTrue();
        assertThat(responses).isEmpty(); verify(dispatcher,never()).acceptBroadcast(anyString(),any());
        transport.release.countDown(); waitResponses(1);
        assertThat(responses.getFirst().get("status")).isEqualTo("accepted");
        transport.receiver.accept(transport.values.getFirst());
        await().untilAsserted(() -> verify(dispatcher).acceptBroadcast(eq("lobby"),argThat(m -> m.getPayload().contains("node-a"))));
        assertThat(transport.values).hasSize(1);
    }

    /** 首条发布结果未知时，不能重发相同消息，也不能给发送者伪造确定失败。 */
    @Test void uncertainPublishIsUnknownAndNeverRetried() throws Exception {
        transport.error=new TimeoutException(); create(4,1000); send("first"); waitResponses(1);
        assertThat(responses.getFirst().get("status")).isEqualTo("unknown");
        assertThat(transport.values).hasSize(1);
        assertThat(metrics.get("lab.messages.unknown").counter().count()).isEqualTo(1);
    }

    /** 确定未发布与总线未就绪走rejected，不能被计为unknown或accepted。 */
    @Test void unavailableBeforePublishIsDefinitiveRejection() throws Exception {
        transport.error=new ClusterTransport.NotAttempted("offline"); create(4,1000); send("first"); waitResponses(1);
        assertThat(responses.getFirst().get("status")).isEqualTo("rejected");
        transport.online=false;
        assertThatThrownBy(() -> send("second")).isInstanceOf(ProtocolException.class).hasMessage("bus_unavailable");
    }

    /** 容量包含在途发布；满时立即拒绝，不让调用线程去执行Redis操作。 */
    @Test void publishFullRejectsWhileFirstIsInFlight() throws Exception {
        transport.block=true; create(1,1000); send("first");
        assertThat(transport.entered.await(2,TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> send("second")).hasMessage("bus_publish_full");
        transport.release.countDown(); waitResponses(1);
        await().until(() -> metrics.get("lab.cluster.publish.bytes").gauge().value()==0);
    }

    /** 旧任务在队列里过期就拒绝，不调用PUBLISH；第一条的替身延迟不代表真实网络成绩。 */
    @Test void queuedPublicationExpiresWithoutBeingPublished() throws Exception {
        transport.block=true; create(2,1000); send("first");
        assertThat(transport.entered.await(2,TimeUnit.SECONDS)).isTrue();
        // 真实线程调度可能超过1ms；给首任务足够启动时间，另让等待任务明确超过1秒，不靠极短时间碰运气。
        send("second"); Thread.sleep(1100); transport.release.countDown(); waitResponses(2);
        assertThat(transport.values).hasSize(1);
        assertThat(responses.stream().anyMatch(r -> "bus_publish_expired".equals(r.get("reason")))).isTrue();
    }

    /**
     * 房间在等待发布期间被最后一名成员清空，就不能把旧生命周期的弹幕继续发到总线。
     * 第一条由闩锁固定在网络边界，第二条留在队列；退休后仅第一条已经尝试的发布可以确认。
     */
    @Test void retiredRoomCancelsQueuedPublicationBeforeNetworkAttempt() throws Exception {
        // 房间退休由Registry测试验证；这里用spy仅控制跨包只读retired状态，固定发布线程的交错。
        var retiring=spy(room);
        var membership=new ConnectionRegistry.Membership(retiring,"测试者");
        transport.block=true; create(2,5000);
        ingress.submit(sender,membership,"in-flight","你好",10);
        assertThat(transport.entered.await(2,TimeUnit.SECONDS)).isTrue();
        ingress.submit(sender,membership,"waiting","你好",10);
        when(retiring.retired()).thenReturn(true);
        transport.release.countDown(); waitResponses(2);
        assertThat(transport.values).hasSize(1);
        assertThat(responses).anySatisfy(response -> {
            assertThat(response.get("clientMessageId")).isEqualTo("waiting");
            assertThat(response.get("status")).isEqualTo("rejected");
            assertThat(response.get("reason")).isEqualTo("room_closed");
        });
        await().until(() -> metrics.get("lab.cluster.publish.messages").gauge().value()==0);
    }

    /**
     * 发布线程的正式编码可能失败：这发生在准入编码之后，但尚未调用Redis，必须明确拒绝。
     * 再提交一条证明工作线程没有随异常退出；失败任务仍须归还条数与字节预算。
     */
    @Test void encodingFailureRejectsWithoutPublishingAndWorkerContinues() throws Exception {
        var template=broadcaster.prepare(sender,member,"template","你好",10,1);
        when(broadcaster.prepare(any(),any(),anyString(),anyString(),anyLong(),anyLong()))
                .thenReturn(template)
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("synthetic encoding failure") { })
                .thenReturn(template);
        create(2,5000); send("broken"); waitResponses(1);
        assertThat(responses.getFirst().get("status")).isEqualTo("rejected");
        assertThat(responses.getFirst().get("reason")).isEqualTo("bus_encoding_failed");
        assertThat(transport.values).isEmpty();
        send("healthy"); waitResponses(2);
        assertThat(responses.get(1).get("status")).isEqualTo("accepted");
        assertThat(transport.values).hasSize(1);
        await().until(() -> metrics.get("lab.cluster.publish.bytes").gauge().value()==0);
    }

    /**
     * 汉字的UTF-8字节数大于String字符数；两端都应按完整信封字节拒绝超过4KiB的事件。
     * 先建立一条合法事件供接收端篡改，确认不是只覆盖ASCII长度的快捷拒绝分支。
     */
    @Test void multibyteEnvelopeLimitAppliesToBothPublishingAndReceiving() throws Exception {
        create(2,5000); send("valid"); waitResponses(1);
        String content="汉".repeat(1400);
        assertThatThrownBy(() -> ingress.submit(sender,member,"large",content,10))
                .hasMessage("bus_event_too_large");
        var envelope=(com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(transport.values.getFirst());
        ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("event")).put("text",content);
        String oversized=json.writeValueAsString(envelope);
        assertThat(oversized.length()).isLessThan(4096);
        assertThat(oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(4096);
        transport.receiver.accept(oversized);
        assertThat(metrics.get("lab.cluster.dropped").tag("reason","event_too_large").counter().count()).isEqualTo(1);
        verify(dispatcher,never()).acceptBroadcast(anyString(),any());
        assertThat(transport.values).hasSize(1);
    }

    /** 接收队列也计在途：解析/本地调度被挡住时，再来的事件会丢弃，网络回调不会阻塞。 */
    @Test void receiveFullAndMalformedEventsAreObservable() throws Exception {
        create(1,1000); send("first"); waitResponses(1);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(2,TimeUnit.SECONDS); return true; })
                .when(dispatcher).acceptBroadcast(anyString(),any());
        String event=transport.values.getFirst(); transport.receiver.accept(event);
        assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue(); transport.receiver.accept(event);
        assertThat(metrics.get("lab.cluster.dropped").tag("reason","receive_full").counter().count()).isEqualTo(1);
        release.countDown();
        await().until(() -> metrics.get("lab.cluster.receive.messages").gauge().value()==0);
        transport.receiver.accept("{bad");
        await().until(() -> metrics.find("lab.cluster.dropped").tag("reason","invalid_event").counter()!=null);
        transport.receiver.accept("x".repeat(4097));
        assertThat(metrics.get("lab.cluster.dropped").tag("reason","event_too_large").counter().count()).isEqualTo(1);
    }

    /** 本地房间满只影响订阅端，不能重新发布或修改已经给出的accepted。 */
    @Test void localQueueRejectionDoesNotRewriteAcknowledgement() throws Exception {
        create(2,1000); send("first"); waitResponses(1);
        when(dispatcher.acceptBroadcast(anyString(),any())).thenThrow(new ProtocolException("room_queue_full"));
        transport.receiver.accept(transport.values.getFirst());
        await().until(() -> metrics.find("lab.cluster.dropped").tag("reason","room_queue_full").counter()!=null);
        assertThat(responses).hasSize(1); assertThat(responses.getFirst().get("status")).isEqualTo("accepted");
        assertThat(transport.values).hasSize(1);
    }

    /**
     * 来源入口允许通过配置提高正文上限；订阅端只执行总线信封约束，不能另套固定200字限制。
     * 这里模拟已通过来源入口校验的201个ASCII字符，确认accepted后仍会真正交给本地调度。
     */
    @Test void receivedTextIsNotRestrictedByTheDefaultInputLimit() throws Exception {
        create(2,1000);
        String content="a".repeat(201);
        ingress.submit(sender,member,"long-text",content,10);
        waitResponses(1);
        assertThat(responses.getFirst().get("status")).isEqualTo("accepted");
        transport.receiver.accept(transport.values.getFirst());
        await().atMost(2,TimeUnit.SECONDS).untilAsserted(() ->
                verify(dispatcher).acceptBroadcast(eq("lobby"),argThat(message -> {
                    try { return json.readTree(message.getPayload()).path("text").asText().equals(content); }
                    catch (Exception invalid) { return false; }
                })));
    }

    /**
     * 大整数转long会截断：2^64+1的低64位恰好是1，但它不是协议版本1。
     * 先送坏版本、再送合法版本作为接收线程的处理屏障，确认坏事件没有进入房间调度。
     */
    @Test void oversizedIntegerVersionCannotMasqueradeAsVersionOne() throws Exception {
        create(4,1000); send("version"); waitResponses(1);
        String valid=transport.values.getFirst();
        var invalid=(com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(valid);
        invalid.put("schemaVersion",new java.math.BigInteger("18446744073709551617"));
        transport.receiver.accept(json.writeValueAsString(invalid));
        transport.receiver.accept(valid);
        await().atMost(2,TimeUnit.SECONDS).until(() -> metrics.get("lab.cluster.receive.messages").gauge().value()==0);
        verify(dispatcher,times(1)).acceptBroadcast(anyString(),any());
        assertThat(metrics.get("lab.cluster.dropped").tag("reason","invalid_event").counter().count()).isEqualTo(1);
    }

    /** Redis模式不能静默回退为同步输出；错误模式在启动组件时直接失败。 */
    @Test void invalidModesFailAtConstruction() {
        when(dispatcher.enabled()).thenReturn(false);
        assertThatThrownBy(() -> create(2,1000)).hasMessage("redis requires fair+buffered");
    }

    /** 停机取消等待项、归还两条桥接队列预算，并关闭网络资源；重复关闭也安全。 */
    @Test void shutdownReleasesPendingBudgets() throws Exception {
        transport.block=true; create(2,1000); send("first");
        assertThat(transport.entered.await(2,TimeUnit.SECONDS)).isTrue(); send("second");
        transport.release.countDown(); ingress.shutdown();
        assertThat(metrics.get("lab.cluster.publish.messages").gauge().value()).isZero();
        assertThat(metrics.get("lab.cluster.receive.bytes").gauge().value()).isZero();
        assertThat(transport.closed).isTrue();
    }

    static final class FakeTransport implements ClusterTransport {
        volatile boolean online=true,block,closed;
        Exception error;
        Consumer<String> receiver;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final List<String> values=new CopyOnWriteArrayList<>();
        public void start(Consumer<String> receiver) { this.receiver=receiver; }
        public boolean ready() { return online && !closed; }
        public long publish(String value) throws Exception {
            values.add(value); entered.countDown(); if(block) release.await(2,TimeUnit.SECONDS);
            if(error!=null) throw error; return 2;
        }
        public void close() { closed=true; }
    }
}
