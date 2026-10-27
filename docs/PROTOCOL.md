# WebSocket 协议 v0.1

当前实现：Spring MVC 原生 WebSocket，端点 `/ws`，文本 JSON，同源浏览器连接。
每条连接最多一个活动房间；普通弹幕包含发送者自身。不提供历史消息、认证或跨重连去重。

新增可选Redis双节点模式，默认local协议语义不变，运行说明见 [双节点文档](CLUSTER.md)。Redis模式的danmaku增加`messageId`（服务器UUID）和`originNodeId`；原`runId+roomInstanceId+roomSeq`描述来源节点的流，接收节点不改写。joined的roomInstanceId仍是本地房间实例，远端广播可以具有不同实例标识；跨来源不比较单一全局序号。

Redis模式的accepted仅表示PUBLISH得到Redis执行确认，不代表订阅节点处理或观众接收。已尝试发布但超时/断线无法确认时返回`{"type":"send_result","status":"unknown","clientMessageId":"...","reason":"bus_publish_unknown"}`，客户端不得自动重发。发布前队列满/过期/未就绪等确定失败仍为rejected，原因包括`bus_publish_full`、`bus_publish_expired`、`bus_unavailable`。两节点各自执行输入限流，集群不共享房间额度。订阅恢复不补发断线期间消息。

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

`lab.dispatch.mode=direct` 时，`accepted` 表示通过输入准入，在房间锁内确认并直发或投递连接邮箱。`lab.dispatch.mode=fair` 时表示已进入有界房间队列并分配序号；要求输出模式 buffered。都不表示所有输出成功入队或客户端已收到；fair 的确认在放锁后投递，可能晚于广播到达。

```json
{"type":"danmaku","runId":"server-run-uuid","roomId":"lobby","roomInstanceId":"room-lifetime-uuid","roomSeq":1,"clientMessageId":"unique-in-this-run","clientSentAt":1792674000000,"senderId":"server-connection-id","nickname":"观察员","text":"你好"}
```

原调度以取得分发锁的顺序生成 `roomSeq`；fair 以成功入房间 FIFO 的顺序生成序号，成员快照在首次实际分发时确定，跨批次复用。同房间前一条未遍历完不开始后一条；容量拒绝不占序号。buffered 邮箱 FIFO 保持已接收序号递增；入口过期/取消和输出淘汰可能留下缺口。每连接弹幕与控制回复统一串行写入。

最后一个成员离开后删除房间，重新创建同名房间会产生新 `roomInstanceId`，序号从 1 开始。消息唯一范围为 `runId + roomInstanceId + roomSeq`；不能只看房间名称和序号。

## 拒绝、心跳与关闭

可识别的 `send` 请求失败返回：

```json
{"type":"send_result","status":"rejected","clientMessageId":"unique-in-this-run","reason":"not_joined"}
```

其他请求失败返回 `{"type":"error","reason":"..."}`。完整帧过大在 JSON 解析前拦截，因此返回 `error/frame_too_large`，不提取消息 ID；超过容器接收上限也可能直接以 1009 关闭。

稳定原因包括 `invalid_json`、`invalid_message`、`unknown_type`、`invalid_room_id`、`invalid_nickname`、`invalid_text`、`invalid_clientMessageId`、`invalid_client_sent_at`、`not_joined`、`connection_rate_limit`、`room_rate_limit`、`room_limit`、`frame_too_large`。fair 另有 `room_queue_full`、`room_global_budget`、`room_scheduler_full`、`room_closed`、`server_stopping`。

`{"type":"ping"}` 返回 `{"type":"pong"}`。浏览器每 15 秒发送应用心跳，45 秒没有 pong 时主动重连；容器空闲超时为 60 秒。
浏览器使用最高 30 秒、带随机抖动的指数退避，不补发消息。连接上限在 WebSocket 升级后检查，超过上限以 1013 关闭。二进制帧不支持。

## 当前模式的边界

- 连接上限 2,000，房间上限 1,000；通过 `lab.*` 配置修改。
- 单连接每秒 5 条、房间每秒 50 条弹幕，令牌桶允许一秒额度的突发。额度拒绝后不退还已消耗的其他层额度。
- 2026-10-24 已改为向原生 Jakarta Session 用户属性写入 Long 类型的 5 秒同步发送超时；旧代码仅写 Spring 业务 Map，Tomcat 未读到时默认 20 秒。单次发送超时不是整轮广播、排队或客户端接收延迟上限。
- direct 模式逐连接同步发送，慢连接仍可能拖住房间锁。buffered 模式有每连接 256 条/64 KiB、全局 128 MiB 的输出预算，均包括在途条目；普通排队弹幕过期/过载时淘汰，控制回复不能静默丢弃，无法保留时断连。
- 2026-10-26的buffered使用原生容器异步写，每连接最多一条在途；8个共享提交线程不等网络完成。入队成功、提交调用返回、容器写完成和客户端收到是四个不同阶段。fair和邮箱仍共享容器/主机资源，不能声称任意负载隔离。
- `lab.deliveries.submitted`在direct表示同步发送成功返回，在新版buffered表示完成回调成功；客户端接收量仍必须独立测量。详细边界见 [异步输出](ASYNC_OUTBOUND.md)。
