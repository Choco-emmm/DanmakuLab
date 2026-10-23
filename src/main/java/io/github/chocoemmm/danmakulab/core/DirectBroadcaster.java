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
 * 直接广播基线：一条输入通过校验与限流后，在当前调用线程里逐个向房间成员发送。
 * 它同时负责把响应转换成 JSON，确保控制响应和弹幕最终都进入 ClientConnection.send。
 *
 * 这份基线故意保留同步发送路径，方便后续与有界缓冲方案做对照。
 * 一个慢连接可能拖慢本轮广播，当前还不能声称已解决慢消费者或热点资源隔离。
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
            // 使用 Map 组装协议对象；LinkedHashMap 让输出字段顺序稳定，方便阅读抓包和日志。
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
            var message = encode(event);
            // accepted 统计输入条数；expected 统计投递次数，两者口径不同。
            // 例如 10 位观众的一条弹幕：accepted 加 1，expected 加 10。
            metrics.counter("lab.messages.accepted").increment();
            metrics.counter("lab.deliveries.expected").increment(recipients.size());
            // 先回复发送者“已通过准入”，不代表下面的所有观众已经收到。
            // 当前基线没有应用入口队列；未来优化模式的 accepted 才表示成功进入有界队列。
            reply(sender, Map.of("type", "send_result", "status", "accepted",
                    "clientMessageId", clientMessageId, "roomSeq", sequence));
            for (var recipient : recipients) {
                // 一个接收者失败会返回 false 并清理，循环仍尝试给其余成员发送。
                if (reply(recipient, message)) {
                    // 仅说明服务端发送调用正常返回；真实接收量必须由客户端另外统计。
                    metrics.counter("lab.deliveries.submitted").increment();
                }
            }
        }
    }

    /** 控制响应使用的入口：先编码，随后与普通弹幕共用下面的实际发送路径。 */
    public boolean reply(ClientConnection connection, Map<String, ?> event) throws JsonProcessingException {
        return reply(connection, encode(event));
    }

    /** 统一处理同步发送与连接失效；返回值只描述本次发送调用是否成功。 */
    private boolean reply(ClientConnection connection, TextMessage message) {
        try {
            connection.send(message);
            return true;
        } catch (IOException | IllegalStateException failure) {
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
