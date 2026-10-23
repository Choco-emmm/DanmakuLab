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

`accepted` 表示通过准入；基线在房间锁内确认并直接广播，没有应用入口队列。未来优化模式表示接纳到有界入口队列，均不表示所有客户端收到。

```json
{"type":"danmaku","runId":"server-run-uuid","roomId":"lobby","roomInstanceId":"room-lifetime-uuid","roomSeq":1,"clientMessageId":"unique-in-this-run","clientSentAt":1792674000000,"senderId":"server-connection-id","nickname":"观察员","text":"你好"}
```

房间内以取得分发锁的顺序生成 `roomSeq`，对该次成员快照逐连接同步发送。每个连接的弹幕、确认和心跳统一串行写入；不同控制消息可以穿插，但同房间实例的弹幕序号递增。

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

## 当前基线的边界

- 连接上限 2,000，房间上限 1,000；通过 `lab.*` 配置修改。
- 单连接每秒 5 条、房间每秒 50 条弹幕，令牌桶允许一秒额度的突发。额度拒绝后不退还已消耗的其他层额度。
- 代码向 Spring Session 属性写入 5 秒同步发送超时值；其向底层容器的传递和实际生效路径尚待核查，不能把它当作已验证的等待上限。[停读实验](results/2026-10-23/slow-report.md)已观察到约 20 秒延迟及同房间发送等待。
- 尚无连接有界发送邮箱、全局发送缓冲预算、队列过期清理或公平调度，不声称已解决慢消费者和热点隔离。
- 服务端 `lab.deliveries.submitted` 只表示发送调用返回，客户端接收量必须由客户端测量。
