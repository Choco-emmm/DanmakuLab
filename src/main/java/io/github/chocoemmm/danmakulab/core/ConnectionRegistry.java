package io.github.chocoemmm.danmakulab.core;

import io.github.chocoemmm.danmakulab.config.LabProperties;
import io.github.chocoemmm.danmakulab.protocol.ProtocolException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接与房间的统一登记处，负责维护“四张表”之间的一致性。
 *
 * 例如连接 A 加入 lobby：connections 找到 A 的连接包装，memberships 记录 A 所属房间，
 * rooms 找到 lobby 的 Room 对象，members 让广播器查到 lobby 中有哪些连接。
 *
 * 这些关系必须一起更新，所以当前使用普通 Map + synchronized 方法统一保护。
 * 仅换成 ConcurrentHashMap 只能保护单个 Map 操作，不能自动保证换房涉及的多张表同时正确。
 * 锁内只处理内存关系；网络发送由广播器在取得快照、释放 Registry 锁之后执行。
 */
@Component
public class ConnectionRegistry {
    private final LabProperties properties;
    private final OutboundService outbound;
    // 连接 ID -> 连接包装。已经建立但尚未加入房间的连接也在这里。
    private final Map<String, ClientConnection> connections = new HashMap<>();
    // 连接 ID -> 当前房间与显示昵称。不存在这条记录意味着尚未加入房间。
    private final Map<String, Membership> memberships = new HashMap<>();
    // 客户端使用的房间 ID -> 当前存活的 Room 实例。最后一个成员离开时回收。
    private final Map<String, Room> rooms = new HashMap<>();
    // Room 实例 ->（连接 ID -> 连接包装），广播时据此找到接收者。
    private final Map<Room, Map<String, ClientConnection>> members = new HashMap<>();

    public ConnectionRegistry(LabProperties properties, MeterRegistry metrics) {
        this(properties, metrics, null);
    }

    /** Spring 使用带输出资源的构造器；上面的便捷构造器用于不涉及网络的直接模式单元测试。 */
    @Autowired
    public ConnectionRegistry(LabProperties properties, MeterRegistry metrics, OutboundService outbound) {
        this.properties = properties;
        this.outbound = outbound;
        // gauge 是按采集时刻读取的当前值，不是不断累加的事件计数。
        // 方法引用使采集器需要数据时调用 connectionCount()/roomCount()。
        metrics.gauge("lab.connections", this, ConnectionRegistry::connectionCount);
        metrics.gauge("lab.rooms", this, ConnectionRegistry::roomCount);
    }

    /** 建连后登记 Session；检查容量与插入同在一个锁内，避免并发建连突破数量上限。 */
    public synchronized ClientConnection register(WebSocketSession session) {
        if (connections.size() >= properties.maxConnections()) {
            throw new ProtocolException("connection_limit");
        }
        var connection = outbound == null ? new ClientConnection(session, properties.connectionMessagesPerSecond())
                : outbound.connection(session, properties.connectionMessagesPerSecond(), () -> remove(session.getId()));
        connections.put(session.getId(), connection);
        return connection;
    }

    /** 按连接 ID 查找；返回 null 表示该连接已被注销或没有成功登记。 */
    public synchronized ClientConnection find(String id) {
        return connections.get(id);
    }

    /**
     * 加入或切换房间，成功后每条连接恰好有一个成员关系。
     * 容量检查在删除旧关系之前完成，因此换房被拒绝时仍保留原房间。
     */
    public synchronized Membership join(ClientConnection connection, String roomId, String nickname) {
        // 不只比较 ID，还比较当前登记的对象身份，避免给已失效的连接重新挂上成员关系。
        if (connections.get(connection.id()) != connection) {
            throw new ProtocolException("connection_closed");
        }
        var previous = memberships.get(connection.id());
        if (previous != null && previous.room().id().equals(roomId)) {
            // 重复加入同名房间只改昵称；沿用同一个 Room，保留序号与已消耗的限流额度。
            var updated = new Membership(previous.room(), nickname);
            memberships.put(connection.id(), updated);
            return updated;
        }
        var target = rooms.get(roomId);
        if (target == null) {
            // 创建新房间需要占一个名额。但如果自己是旧房间最后一人，离开后会释放一个名额，
            // 因此可以在已满的情况下完成“一间换一间”，无需错误地拒绝这次换房。
            boolean replacesLastMember = previous != null && members.get(previous.room()).size() == 1;
            if (rooms.size() >= properties.maxRooms() && !replacesLastMember) {
                throw new ProtocolException("room_limit");
            }
        }
        // 容量检查已通过，现在再移除旧房间关系；detach 必须在 Registry 锁内调用。
        detach(connection.id());
        // 从当前房间表取得目标；不存在时，computeIfAbsent 才执行 lambda 创建 Room。
        target = rooms.computeIfAbsent(roomId, id -> new Room(id, properties.roomMessagesPerSecond()));
        // 内层使用 LinkedHashMap 保留成员插入顺序，使基线广播遍历更容易观察和复现。
        // 按 Room 实例分组，避免同名房间重建后误用旧实例的成员表。
        members.computeIfAbsent(target, ignored -> new LinkedHashMap<>()).put(connection.id(), connection);
        var membership = new Membership(target, nickname);
        memberships.put(connection.id(), membership);
        return membership;
    }

    /** 发送前要求连接已有房间；返回只读 record，包含房间引用和本次使用的昵称。 */
    public synchronized Membership membership(ClientConnection connection) {
        var membership = memberships.get(connection.id());
        if (membership == null) {
            throw new ProtocolException("not_joined");
        }
        return membership;
    }

    /**
     * 复制分发这一刻的成员列表，之后的加入或离开不再改变这份快照。
     * 锁释放后广播器可以安全遍历副本，无需把 Registry 锁一直持有到慢连接发送结束。
     * 快照里的连接仍可能随后关闭，发送层会处理失败；快照不是“保证所有人收到”的承诺。
     */
    public synchronized List<ClientConnection> snapshot(Room room) {
        var roomMembers = members.get(room);
        return roomMembers == null ? List.of() : List.copyOf(roomMembers.values());
    }

    /**
     * 注销连接并离开房间。连接关闭、传输异常和发送失败都可能触发它。
     * 重复清理也安全，即“幂等”：第二次调用不会再删别人或重复扣减成员数量。
     */
    public void remove(String id) {
        ClientConnection removed;
        synchronized (this) {
            removed = connections.remove(id);
            detach(id);
        }
        // 释放 Registry 锁以后再取消邮箱，避免 Registry 锁与邮箱/生命周期锁互相嵌套。
        if (removed != null) removed.dispose();
    }

    /** 仅删除房间关系，连接登记本身保留；调用方必须已持有当前 Registry 对象的锁。 */
    private void detach(String id) {
        var previous = memberships.remove(id);
        if (previous == null) {
            // 从未加入或已经清理过，无需继续处理。
            return;
        }
        var roomMembers = members.get(previous.room());
        roomMembers.remove(id);
        if (roomMembers.isEmpty()) {
            // 先标记这一代房间结束；调度器下一轮会取消它的待处理输入，不影响同名新实例。
            previous.room().retire();
            // 空房间及时回收，防止不断创建新房间让内存中的房间表永久增长。
            members.remove(previous.room());
            // 同时指定 key 和 value，表达“删除的必须是这一代 Room”，避免误删同名的新实例。
            rooms.remove(previous.room().id(), previous.room());
        }
    }

    /** 当前登记的连接数，由监控 gauge 读取；读取也遵守同一把 Registry 锁。 */
    public synchronized int connectionCount() {
        return connections.size();
    }

    /** 当前非空房间数量。 */
    public synchronized int roomCount() {
        return rooms.size();
    }

    /** 不可变的成员关系数据；record 自动提供 room() 和 nickname() 访问方法。 */
    public record Membership(Room room, String nickname) {
    }
}
