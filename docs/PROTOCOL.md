# WebSocket 协议 v0.1

当前实现：Spring MVC 原生 WebSocket，端点 `/ws`，文本 JSON，同源浏览器连接。
每条连接最多一个活动房间；普通弹幕包含发送者自身。不提供历史消息、认证或跨重连去重。

## 加入与换房

```json
{"type":"join","roomId":"lobby","nickname":"观察员"}
```

成功返回 `joined`，包含 `roomId`、`roomInstanceId` 和服务端 `connectionId`。
房间 ID 为 1–64 位 ASCII 字母、数字、下划线或短横线；昵称非空白、最多 32 个 UTF-16 代码单元。
换房成功后移除旧成员关系；换房因容量拒绝时保留旧成员关系。同房间重复加入只更新昵称，不重置序号或限流。

成员快照在每次分发时确定。换房之前已经取得快照的广播可能稍后到达，客户端按 `roomId` 过滤；不承诺加入确认一定先于所有并发广播到达。需要固定成员计数时，等待全部 `joined` 后再启动发送。

## 发送与接收

```json
{"type":"send","clientMessageId":"unique-in-this-run","text":"你好","clientSentAt":1792674000000}
```

`clientMessageId` 为非空白文本、最多 64 个 UTF-16 代码单元；`text` 非空白、最多 200 个 UTF-16 代码单元；`clientSentAt` 为非负 long 整数毫秒时间戳，用于客户端测量，不作为排序依据。
完整输入 JSON 按 UTF-8 计最多 1,024 字节，包括字段名、转义与元数据。

```json
{"type":"send_result","status":"accepted","clientMessageId":"unique-in-this-run","roomSeq":1}
```

`accepted` 表示通过输入准入；direct 在房间锁内确认并直接发送，buffered 在房间锁内确认并按序投递每连接有界邮箱。两种模式当前均无房间入口队列，不表示所有输出成功入队或客户端已经收到。

```json
{"type":"danmaku","runId":"server-run-uuid","roomId":"lobby","roomInstanceId":"room-lifetime-uuid","roomSeq":1,"clientMessageId":"unique-in-this-run","clientSentAt":1792674000000,"senderId":"server-connection-id","nickname":"观察员","text":"你好"}
```

房间内以取得分发锁的顺序生成 `roomSeq`，对该次成员快照逐连接直发或入邮箱。buffered 的邮箱 FIFO（先进先出：先入队的先写）保持序号顺序；淘汰未发送旧弹幕可能留下序号缺口。每个连接的弹幕、确认和心跳统一串行写入；不同控制消息可以穿插，但同房间实例的已接收弹幕序号递增。

最后一个成员离开后删除房间，重新创建同名房间会产生新 `roomInstanceId`，序号从 1 开始。消息唯一范围为 `runId + roomInstanceId + roomSeq`；不能只看房间名称和序号。

## 拒绝、心跳与关闭

可识别的 `send` 请求失败返回：

```json
{"type":"send_result","status":"rejected","clientMessageId":"unique-in-this-run","reason":"not_joined"}
```

其他请求失败返回 `{"type":"error","reason":"..."}`。完整帧过大在 JSON 解析前拦截，因此返回 `error/frame_too_large`，不提取消息 ID；超过容器接收上限也可能直接以 1009 关闭。

稳定原因包括 `invalid_json`、`invalid_message`、`unknown_type`、`invalid_room_id`、`invalid_nickname`、`invalid_text`、`invalid_clientMessageId`、`invalid_client_sent_at`、`not_joined`、`connection_rate_limit`、`room_rate_limit`、`room_limit`、`frame_too_large`。

`{"type":"ping"}` 返回 `{"type":"pong"}`。浏览器每 15 秒发送应用心跳，45 秒没有 pong 时主动重连；容器空闲超时为 60 秒。
浏览器使用最高 30 秒、带随机抖动的指数退避，不补发消息。连接上限在 WebSocket 升级后检查，超过上限以 1013 关闭。二进制帧不支持。

## 当前模式的边界

- 连接上限 2,000，房间上限 1,000；通过 `lab.*` 配置修改。
- 单连接每秒 5 条、房间每秒 50 条弹幕，令牌桶允许一秒额度的突发。额度拒绝后不退还已消耗的其他层额度。
- 2026-10-24 已改为向原生 Jakarta Session 用户属性写入 Long 类型的 5 秒同步发送超时；旧代码仅写 Spring 业务 Map，Tomcat 未读到时默认 20 秒。单次发送超时不是整轮广播、排队或客户端接收延迟上限。
- direct 模式逐连接同步发送，慢连接仍可能拖住房间锁。buffered 模式有每连接 256 条/64 KiB、全局 128 MiB 的输出预算，均包括在途条目；普通排队弹幕过期/过载时淘汰，控制回复不能静默丢弃，无法保留时断连。
- buffered 仍可能因有限发送池被多个慢连接占满而产生干扰；尚无房间入口队列或房间公平调度。详细语义见 [有界输出](BOUNDED_OUTBOUND.md)，不能声称整个慢消费者/热点隔离验收已完成。
- 服务端 `lab.deliveries.submitted` 只表示发送调用返回，客户端接收量必须由客户端测量。
