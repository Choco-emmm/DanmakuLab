package io.github.chocoemmm.danmakulab.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.chocoemmm.danmakulab.config.*;
import io.github.chocoemmm.danmakulab.core.*;
import io.github.chocoemmm.danmakulab.protocol.ProtocolException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Redis模式广播入口：一条有界发布FIFO与一条有界接收FIFO，各一个工作线程。
 * 发送者也必须等订阅回流，绝不额外本地直发；接收路径不调用submit，防止广播环路。
 * accepted仅表示Redis回复PUBLISH成功；订阅端和输出端仍可能丢弃，真实收齐由客户端统计。
 */
@Component
@ConditionalOnProperty(name="lab.cluster.mode", havingValue="redis")
public final class RedisBroadcastIngress implements BroadcastIngress {
    private final ClusterProperties properties;
    private final ClusterTransport transport;
    private final DirectBroadcaster broadcaster;
    private final FairRoomDispatcher dispatcher;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final BudgetQueue<Publication> publishing;
    private final BudgetQueue<String> receiving;
    private final Thread publisher;
    private final Thread receiver;
    private volatile boolean stopping;

    public RedisBroadcastIngress(ClusterProperties properties, OutboundProperties outbound,
            FairRoomDispatcher dispatcher, DirectBroadcaster broadcaster, ObjectMapper json,
            MeterRegistry metrics, ClusterTransport transport) {
        if (!dispatcher.enabled() || !outbound.buffered()) throw new IllegalArgumentException("redis requires fair+buffered");
        if (properties.nodeId().equals("local")) throw new IllegalArgumentException("redis requires an explicit unique node-id");
        this.properties=properties; this.transport=transport; this.dispatcher=dispatcher;
        this.broadcaster=broadcaster; this.json=json; this.metrics=metrics;
        publishing=new BudgetQueue<>(properties.queueMessages(),properties.queueBytes());
        receiving=new BudgetQueue<>(properties.queueMessages(),properties.queueBytes());
        metrics.gauge("lab.cluster.publish.messages",publishing,q->q.count());
        metrics.gauge("lab.cluster.publish.bytes",publishing,q->q.bytes());
        metrics.gauge("lab.cluster.receive.messages",receiving,q->q.count());
        metrics.gauge("lab.cluster.receive.bytes",receiving,q->q.bytes());
        metrics.gauge("lab.cluster.ready",transport,t->t.ready() ? 1 : 0);
        publisher=worker("lab-redis-publish",this::publishLoop);
        receiver=worker("lab-redis-receive",this::receiveLoop);
        transport.start(this::receive);
    }

    private static Thread worker(String name,Runnable work) {
        var thread=new Thread(work,name); thread.setDaemon(true); thread.start(); return thread;
    }

    @Override public void submit(ClientConnection sender, ConnectionRegistry.Membership membership,
                                 String id,String text,long sentAt) throws IOException {
        if (stopping || !transport.ready()) throw new ProtocolException("bus_unavailable");
        var room=membership.room();
        String messageId=UUID.randomUUID().toString();
        // 编码在队列锁外进行。先用long最大值计算保守上界，排队对象只持有有界字段及连接引用。
        String envelope=encode(sender,membership,id,text,sentAt,messageId,Long.MAX_VALUE);
        int size=envelope.getBytes(StandardCharsets.UTF_8).length;
        if (size > properties.maxEventBytes()) throw new ProtocolException("bus_event_too_large");
        if (room.retired()) throw new ProtocolException("room_closed");
        var pending=new Publication(sender,membership,id,text,sentAt,messageId,size,System.nanoTime());
        if (!publishing.offer(pending,size)) throw new ProtocolException(stopping ? "bus_unavailable" : "bus_publish_full");
    }

    /** record是不可变任务载体，queuedAt使用单调时钟纳秒，不受系统时钟调整影响。 */
    private record Publication(ClientConnection sender,ConnectionRegistry.Membership membership,
            String id,String text,long sentAt,String messageId,int size,long queuedAt) { }

    private String encode(ClientConnection sender,ConnectionRegistry.Membership membership,String id,
                          String text,long sentAt,String messageId,long sequence) throws IOException {
        ObjectNode event=(ObjectNode) json.readTree(broadcaster.prepare(sender,membership,id,text,sentAt,sequence).getPayload());
        event.put("messageId",messageId); event.put("originNodeId",properties.nodeId());
        return json.writeValueAsString(Map.of("schemaVersion",1,"event",event));
    }

    private void publishLoop() {
        while (!stopping) {
            try {
                var slot=publishing.take();
                if (slot == null) return;
                try (slot) { publishOne(slot.value); }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** 序号按单发布线程的处理顺序分配；只短暂持房间锁，Redis网络等待始终在锁外。 */
    private void publishOne(Publication p) {
        long sequence;
        var room=p.membership.room();
        if (System.nanoTime()-p.queuedAt >= properties.maxAgeMillis()*1_000_000L) { result(p,"rejected","bus_publish_expired",0); return; }
        synchronized (room) {
            if (room.retired()) { sequence=0; }
            else sequence=room.nextSequence();
        }
        if (sequence == 0) { result(p,"rejected","room_closed",0); return; }
        String value;
        try { value=encode(p.sender,p.membership,p.id,p.text,p.sentAt,p.messageId,sequence); }
        catch (IOException invalid) { result(p,"rejected","bus_encoding_failed",sequence); return; }
        try {
            long subscribers=transport.publish(value);
            metrics.counter("lab.cluster.published").increment();
            metrics.counter("lab.cluster.publish.subscribers").increment(subscribers);
            result(p,"accepted","",sequence);
        } catch (ClusterTransport.NotAttempted unavailable) {
            result(p,"rejected","bus_unavailable",sequence);
        } catch (Exception uncertain) {
            // 已调用PUBLISH后超时，不能知道Redis是否广播过；不用重试制造可能的重复。
            result(p,"unknown","bus_publish_unknown",sequence);
        }
    }

    private void result(Publication p,String status,String reason,long sequence) {
        if (status.equals("accepted")) metrics.counter("lab.messages.accepted").increment();
        else if (status.equals("rejected")) metrics.counter("lab.messages.rejected","reason",reason).increment();
        else metrics.counter("lab.messages.unknown").increment();
        metrics.counter("lab.cluster.publish.results","status",status,"reason",reason).increment();
        try { broadcaster.reply(p.sender,Map.of("type","send_result","status",status,"reason",reason,
                "clientMessageId",p.id,"messageId",p.messageId,"originNodeId",properties.nodeId(),"roomSeq",sequence)); }
        catch (IOException failedFeedback) { p.sender.closeAfterFailure(); }
    }

    /** Redis网络回调仅做长度检查和非阻塞offer；满时计丢弃，不执行JSON解析、房间遍历或网络发送。 */
    private void receive(String value) {
        if (stopping) return;
        if (value.length()>properties.maxEventBytes()) { drop("event_too_large"); return; }
        int size=value.getBytes(StandardCharsets.UTF_8).length;
        if (size>properties.maxEventBytes()) { drop("event_too_large"); return; }
        if (!receiving.offer(value,size)) drop("receive_full");
    }

    private void receiveLoop() {
        while (!stopping) {
            try {
                var slot=receiving.take();
                if (slot == null) return;
                try (slot) {
                    var envelope=json.readTree(slot.value);
                    if (!envelope.path("schemaVersion").isIntegralNumber()
                            || !envelope.path("schemaVersion").canConvertToLong()
                            || envelope.path("schemaVersion").longValue()!=1) throw new IllegalArgumentException();
                    var event=envelope.path("event");
                    validateEvent(event);
                    metrics.counter("lab.cluster.received").increment();
                    // 必须保留原事件，不能换成本节点runId/roomInstanceId/roomSeq。
                    if (dispatcher.acceptBroadcast(event.path("roomId").textValue(),new TextMessage(json.writeValueAsString(event))))
                        metrics.counter("lab.cluster.local.enqueued").increment();
                    else metrics.counter("lab.cluster.local.ignored").increment();
                } catch (ProtocolException full) { drop(full.getMessage()); }
                  catch (Exception invalid) { drop("invalid_event"); }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** 总线属于内部信任域，但仍校验版本和字段，避免坏消息让共享接收线程退出或污染客户端统计。 */
    static void validateEvent(JsonNode event) {
        if (!event.isObject() || !"danmaku".equals(event.path("type").asText())) throw new IllegalArgumentException();
        for (String field: new String[]{"messageId","originNodeId","runId","roomInstanceId","clientMessageId","senderId"}) {
            if (!event.path(field).isTextual() || event.path(field).asText().isBlank() || event.path(field).asText().length()>128) throw new IllegalArgumentException();
        }
        if (!event.path("roomId").asText().matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException();
        // 正文上限由来源节点的lab.max-text-length校验；接收端不能重新套默认200或本节点的输入上限。
        // 例如来源允许300字时，201字仍是合法广播。接收回调已限制完整信封最多4KiB，内存仍有界。
        if (!event.path("text").isTextual() || event.path("text").asText().isBlank()) throw new IllegalArgumentException();
        // 昵称是各节点共享的固定协议约束，与可配置的正文输入上限不同。
        if (!event.path("nickname").isTextual() || event.path("nickname").asText().isBlank()
                || event.path("nickname").asText().length()>32) throw new IllegalArgumentException();
        for (String field: new String[]{"roomSeq","clientSentAt"}) {
            if (!event.path(field).isIntegralNumber() || !event.path(field).canConvertToLong()
                    || event.path(field).longValue()<(field.equals("roomSeq") ? 1 : 0)) throw new IllegalArgumentException();
        }
    }

    private void drop(String reason) { metrics.counter("lab.cluster.dropped","reason",reason).increment(); }

    @PreDestroy
    public void shutdown() {
        stopping=true;
        for (var pending:publishing.stop()) result(pending,"rejected","bus_unavailable",0);
        int discarded=receiving.stop().size();
        if (discarded>0) metrics.counter("lab.cluster.dropped","reason","shutdown").increment(discarded);
        publisher.interrupt(); receiver.interrupt();
        for (var thread: new Thread[]{publisher,receiver}) {
            try { thread.join(properties.timeoutMillis()+2000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        transport.close();
    }
}
