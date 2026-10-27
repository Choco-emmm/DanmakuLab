package io.github.chocoemmm.danmakulab.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 房间串行分发：direct 模式逐个同步发送；buffered 模式逐个快速投递到连接邮箱。
 * 它同时负责把响应转换成 JSON，确保控制响应和弹幕最终都进入 ClientConnection.send。
 *
 * 保留直接广播路径用于对照；FairRoomDispatcher 复用本类编码和统一输出入口。
 */
@Component
public class DirectBroadcaster {
    private final ConnectionRegistry registry;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    // 每次服务启动生成一次，区分不同进程运行期间产生的序号。
    private final String runId = UUID.randomUUID().toString();

    public DirectBroadcaster(ConnectionRegistry registry, ObjectMapper json, MeterRegistry metrics) {
        this.registry = registry;
        this.json = json;
        this.metrics = metrics;
    }

    /**
     * 向分发时刻的房间成员发送一条弹幕，成员包含发送者自己。
     * clientMessageId 用于关联请求与统计；clientSentAt 原样传回，供同机客户端计算端到端延迟。
     */
    public void broadcast(ClientConnection sender, ConnectionRegistry.Membership membership,
                          String clientMessageId, String text, long clientSentAt) throws IOException {
        var room = membership.room();
        // 房间锁保护“取快照、分配序号、整轮广播”的相对顺序。
        // 同房间两位发送者即使并发进入，也依次推进；不同 Room 各有自己的锁。
        // 这里与 Registry 的成员锁、ClientConnection 的发送锁是三个不同层次。
        synchronized (room) {
            var recipients = registry.snapshot(room);
            long sequence = room.nextSequence();
            var message = prepare(sender, membership, clientMessageId, text, clientSentAt, sequence);
            metrics.counter("lab.messages.accepted").increment();
            metrics.counter("lab.deliveries.expected").increment(recipients.size());
            // direct 调度路径没有入口队列；确认通过准入，不表示所有观众已收到。
            reply(sender, Map.of("type", "send_result", "status", "accepted",
                    "clientMessageId", clientMessageId, "roomSeq", sequence));
            for (var recipient : recipients) deliver(recipient, message);
        }
    }

    /** 构造一条只读广播；调用一次编码后复用，fair 也按这个完整 JSON 的 UTF-8 大小计预算。 */
    public TextMessage prepare(ClientConnection sender, ConnectionRegistry.Membership membership,
                               String clientMessageId, String text, long clientSentAt, long sequence)
            throws JsonProcessingException {
        var room = membership.room();
        // LinkedHashMap 保持协议字段顺序稳定，方便阅读抓包和日志。
        var event = new LinkedHashMap<String, Object>();
        event.put("type", "danmaku");
        event.put("runId", runId);
        event.put("roomId", room.id());
        event.put("roomInstanceId", room.instanceId());
        event.put("roomSeq", sequence);
        event.put("clientMessageId", clientMessageId);
        event.put("clientSentAt", clientSentAt);
        event.put("senderId", sender.id());
        event.put("nickname", membership.nickname());
        event.put("text", text);
        // 同一条广播只序列化一次，并复用只读 TextMessage，避免每位观众重复做 JSON 编码。
        return encode(event);
    }

    /** 一份观众投递。buffered 的submitted由异步写完成入口增加，不能把入邮箱当成写入完成或观众收到。 */
    public void deliver(ClientConnection recipient, TextMessage message) {
        if (reply(recipient, message, true) && !recipient.buffered()) {
            metrics.counter("lab.deliveries.submitted").increment();
        }
    }

    /** 控制响应使用的入口：先编码，随后与普通弹幕共用下面的实际发送路径。 */
    public boolean reply(ClientConnection connection, Map<String, ?> event) throws JsonProcessingException {
        return reply(connection, encode(event));
    }

    /** 统一处理同步发送与连接失效；返回值只描述本次发送调用是否成功。 */
    private boolean reply(ClientConnection connection, TextMessage message) {
        return reply(connection, message, false);
    }

    private boolean reply(ClientConnection connection, TextMessage message, boolean danmaku) {
        try {
            connection.send(message, danmaku);
            return true;
        } catch (IOException | IllegalStateException failure) {
            if (connection.buffered()) {
                // 邮箱失败已记录具体原因；先请求异步注销/关闭，不能在房间锁里等待网络关闭。
                connection.closeAfterFailure();
                return false;
            }
            // 网络断开或 Session 状态变化都可能导致发送失败；先注销再尽力关闭底层连接。
            // afterConnectionClosed 随后再次清理也是安全的，因为 Registry.remove 可重复调用。
            metrics.counter("lab.writes.failed").increment();
            registry.remove(connection.id());
            connection.closeAfterFailure();
            return false;
        }
    }

    /** Jackson 将 Java 对象转成 JSON 文本，TextMessage 再交给 Spring 的 WebSocket Session。 */
    private TextMessage encode(Map<String, ?> event) throws JsonProcessingException {
        return new TextMessage(json.writeValueAsString(event));
    }
}
