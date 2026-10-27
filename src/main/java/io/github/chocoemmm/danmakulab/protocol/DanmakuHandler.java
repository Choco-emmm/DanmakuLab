package io.github.chocoemmm.danmakulab.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.core.ClientConnection;
import io.github.chocoemmm.danmakulab.core.ConnectionRegistry;
import io.github.chocoemmm.danmakulab.core.DirectBroadcaster;
import io.github.chocoemmm.danmakulab.core.FairRoomDispatcher;
import io.github.chocoemmm.danmakulab.core.BroadcastIngress;
import io.github.chocoemmm.danmakulab.core.LocalBroadcastIngress;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * WebSocket 协议入口：Spring 收到连接事件或文本消息后调用本类。
 * 负责解析、校验、按 type 路由、执行准入限流，再把合法弹幕交给广播器。
 * 房间/连接关系交给 Registry，实际写入交给 Broadcaster，避免入口同时承担所有职责。
 */
@Component
public class DanmakuHandler extends TextWebSocketHandler {
    // 房间 ID 只允许 1~64 位字母、数字、下划线和短横线，便于作为稳定的路由名称。
    private static final Pattern ROOM_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    // Jackson 的 JSON 解析器；JsonNode 表示解析得到的树形字段结构。
    private final ObjectMapper json;
    private final LabProperties properties;
    private final ConnectionRegistry registry;
    private final DirectBroadcaster broadcaster;
    private final MeterRegistry metrics;
    private final BroadcastIngress ingress;

    public DanmakuHandler(ObjectMapper json, LabProperties properties, ConnectionRegistry registry,
                          DirectBroadcaster broadcaster, MeterRegistry metrics, FairRoomDispatcher dispatcher) {
        this(json, properties, registry, broadcaster, metrics, new LocalBroadcastIngress(broadcaster, dispatcher));
    }

    /** Spring按运行模式注入入口；便捷构造器继续支持原有本地单元测试。 */
    @Autowired
    public DanmakuHandler(ObjectMapper json, LabProperties properties, ConnectionRegistry registry,
                          DirectBroadcaster broadcaster, MeterRegistry metrics, BroadcastIngress ingress) {
        this.json = json;
        this.properties = properties;
        this.registry = registry;
        this.broadcaster = broadcaster;
        this.metrics = metrics;
        this.ingress = ingress;
    }

    /** WebSocket 握手成功后登记连接；此时还没有加入任何业务房间。 */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Spring getAttributes() 是业务 Map，Tomcat 不从那里读取发送超时。
        // 必须写入原生 Jakarta Session 的 userProperties，值使用 Long，单位毫秒。
        // 只限制实际单次发送等待；不限制等待房间锁/执行器的时间，也不承诺观众已经收到。
        if (session instanceof StandardWebSocketSession standard) {
            standard.getNativeSession().getUserProperties()
                    .put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 5_000L);
        } else {
            throw new IllegalStateException("This experiment requires a standard Jakarta WebSocket session");
        }
        try {
            registry.register(session);
        } catch (ProtocolException rejected) {
            // 数量限制检查发生在升级成功之后；1013 告知客户端服务暂时无法接纳更多连接。
            metrics.counter("lab.connections.rejected").increment();
            session.close(new CloseStatus(1013, rejected.getMessage()));
        }
    }

    /** 处理一条完整文本消息；二进制消息沿用父类的不支持策略。 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var connection = registry.find(session.getId());
        if (connection == null) {
            // 连接可能因前一次发送失败已被清理，不能继续处理它后续到达的业务消息。
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String clientMessageId = "";
        boolean isSend = false;
        try {
            // 在解析 JSON 前检查完整 UTF-8 大小，防止仅检查正文字数而遗漏元数据和多字节字符。
            // 超限时还不知道请求类型和消息 ID，因此后面会返回通用 error。
            if (message.asBytes().length > properties.maxFrameBytes()) {
                throw new ProtocolException("frame_too_large");
            }
            var root = json.readTree(message.getPayload());
            // 协议要求顶层是对象；null、数组和标量不能按业务消息处理。
            if (root == null || !root.isObject()) {
                throw new ProtocolException("invalid_message");
            }
            String type = text(root, "type", 32);
            isSend = type.equals("send");
            // 尽早保留长度受限的关联 ID，使后续校验失败也能告诉发送者是哪条消息被拒绝。
            // 这里没有承诺去重；相同 clientMessageId 的两次请求仍可能各自被接纳。
            if (isSend && root.path("clientMessageId").isTextual()
                    && root.path("clientMessageId").textValue().length() <= 64) {
                clientMessageId = root.path("clientMessageId").textValue();
            }
            switch (type) {
                case "join" -> {
                    // 先校验再修改成员关系；Registry 负责让换房涉及的多张 Map 一致更新。
                    String roomId = text(root, "roomId", 64);
                    if (!ROOM_ID.matcher(roomId).matches()) {
                        throw new ProtocolException("invalid_room_id");
                    }
                    var membership = registry.join(connection, roomId, text(root, "nickname", 32));
                    broadcaster.reply(connection, Map.of("type", "joined", "roomId", roomId,
                            "roomInstanceId", membership.room().instanceId(), "connectionId", connection.id()));
                }
                case "send" -> send(connection, root);
                // 应用层心跳也必须经过统一发送入口，避免与弹幕同时写同一个 Session。
                case "ping" -> broadcaster.reply(connection, Map.of("type", "pong"));
                default -> throw new ProtocolException("unknown_type");
            }
        } catch (JsonProcessingException invalidJson) {
            // 无法解析时不回显原始内容，使用稳定原因码交给客户端和指标系统识别。
            reject(connection, false, "", "invalid_json");
        } catch (ProtocolException rejected) {
            // 预期的业务拒绝由协议回应，不将它当作服务器崩溃，也不自动关闭正常连接。
            reject(connection, isSend, clientMessageId, rejected.getMessage());
        }
    }

    /** 弹幕输入路径：统计输入 -> 校验字段 -> 确认成员关系 -> 连接限流 -> 房间限流 -> 广播。 */
    private void send(ClientConnection connection, JsonNode root) throws Exception {
        metrics.counter("lab.messages.input").increment();
        String id = text(root, "clientMessageId", 64);
        String content = text(root, "text", properties.maxTextLength());
        var timestamp = root.path("clientSentAt");
        // 时间戳必须是可放入 long 的非负整数，不能把字符串或小数悄悄转换成有效时间。
        // 它只参与测量，消息顺序使用服务端分配的 roomSeq。
        if (!timestamp.isIntegralNumber() || !timestamp.canConvertToLong() || timestamp.longValue() < 0) {
            throw new ProtocolException("invalid_client_sent_at");
        }
        var membership = registry.membership(connection);
        // 两个桶分别保护单连接与整个房间。先消耗连接额度，再消耗房间额度。
        // 房间拒绝时不会退回已消耗的连接额度，这是当前实现明确采用的准入策略。
        if (!connection.admitMessage()) {
            throw new ProtocolException("connection_rate_limit");
        }
        if (!membership.room().admitMessage()) {
            throw new ProtocolException("room_rate_limit");
        }
        // 本地入口保持旧语义；Redis入口只提交发布任务，不额外直发本地，也不锁内等待网络。
        // 同步的准入异常仍由统一catch回应；已排队任务的最终发布结果由入口异步反馈。
        ingress.submit(connection, membership, id, content, timestamp.longValue());
    }

    /** 通用文本校验：要求字段为字符串、非空白且长度不超限，不把其他 JSON 类型自动转成文字。 */
    private String text(JsonNode root, String field, int maxLength) {
        var node = root.path(field);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > maxLength) {
            throw new ProtocolException("invalid_" + field);
        }
        return node.textValue();
    }

    /** 按协议返回拒绝，并按有限的原因码分类计数；不使用用户 ID 或消息 ID 作为指标标签。 */
    private void reject(ClientConnection connection, boolean isSend, String id, String reason) throws Exception {
        metrics.counter("lab.messages.rejected", "reason", reason).increment();
        if (isSend) {
            // 能识别为 send 时，回应 send_result，方便客户端关联一次具体发送。
            broadcaster.reply(connection, Map.of("type", "send_result", "status", "rejected",
                    "clientMessageId", id, "reason", reason));
        } else {
            // 加入、未知类型、解析前超限等错误使用通用 error 响应。
            broadcaster.reply(connection, Map.of("type", "error", "reason", reason));
        }
    }

    /** 正常关闭或底层检测到断连后，回收连接及其房间成员关系。 */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.remove(session.getId());
    }

    /** 传输异常时先清理逻辑关系，再尽力关闭网络连接；随后重复到达的关闭回调可安全处理。 */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        registry.remove(session.getId());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }
}
