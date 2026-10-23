# 第一遍上手：先收发，再沿消息读代码

目标：用两个浏览器页面完成收发、换房和断线实验，然后解释消息经过的六个组件。

## 1. 准备服务

页面地址为 `http://127.0.0.1:8080`。能打开就直接继续，不要再启动第二个占用 8080 的服务。

如果服务没有启动，在项目目录打开 PowerShell：

```powershell
Set-Location D:\coding\DanmakuLab
mvn -B -ntp verify
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar
```

保留终端运行；Ctrl+C 停止服务。Windows 重新打包前先停止运行中的 JAR。

## 2. 用两个页面做实验

把同一个页面地址打开两次，分别叫 A 页、B 页。每个页面点击“连接并加入”后，都会建立自己的 WebSocket 连接。

| 操作 | 应看到的结果 | 对应概念 |
| --- | --- | --- |
| A 页昵称填 A，房间填 lobby，点击“连接并加入” | 右上角显示已连接，房间标题变成 lobby，发送按钮启用 | 连接建立后还要 join |
| B 页昵称填 B，同样加入 lobby | B 页也启用发送 | 两条连接属于同一房间 |
| A 页发“你好，我是 A” | A、B 两页都显示该弹幕 | 广播包含发送者自己 |
| B 页发“收到，我是 B” | A、B 两页都显示回复 | WebSocket 支持双向通信 |
| B 页把房间改成 another，点击“切换房间” | B 页清空列表，房间标题变成 another | 原连接复用，成员关系改变 |
| A 页发“只有 lobby 能看到” | A 页显示，B 页不显示 | 房间隔离 |
| B 页改回 lobby，点击“切换房间” | 不补发刚才漏掉的弹幕，但能收到接下来新发的弹幕 | 普通弹幕无历史补发 |
| B 页点击“断开连接”，A 页再发一条 | B 页不能发送，也不收到这条消息 | 主动断开不自动重连 |
| B 页点击“连接并加入”，A 页再发一条 | B 页重新收到新弹幕，不补发断开期间的消息 | 重新建立连接并加入 |

先完成表格即可。自动退避重连是意外断线的恢复路径，用户主动点击断开会停用重连。

## 3. /ws 为什么进入处理器，页面为什么由同一个服务提供

`WebSocketConfiguration.registerWebSocketHandlers()` 调用 `registry.addHandler(handler, "/ws")`，注册路径和处理器的对应关系。

浏览器中的 `new WebSocket("ws://127.0.0.1:8080/ws")` 发起握手，底层完成 HTTP 升级后，Spring 把连接建立、文本消息和断开等事件交给 `DanmakuHandler` 对应方法。仅在地址栏用普通 HTTP 打开 `/ws` 不是建立 WebSocket 的操作。

演示页面放在 `src/main/resources/static/`，由同一个 Boot 服务提供：启动一个进程即可演示，页面与 WebSocket 同源，也省去单独前端开发服务器和跨域来源配置。页面 HTTP 请求与 WebSocket 是分别建立的请求/连接，共用的是服务地址。

## 4. 怎样区分 join/send/ping，怎样校验和返回拒绝

`DanmakuHandler.handleTextMessage()` 先按 UTF-8 字节数检查完整输入，再解析 JSON，读取 `type` 字段，用 switch 分流：

```json
{"type":"join","roomId":"lobby","nickname":"A"}
```

加入或切换房间。

```json
{"type":"send","clientMessageId":"demo-1","text":"你好","clientSentAt":0}
```

校验正文、时间戳和成员关系，通过连接与房间限流后广播。示例时间戳 0 是合法协议值，但不适合测真实延迟；页面发送时会自动填当前毫秒时间。

```json
{"type":"ping"}
```

应用心跳，服务回复 `pong`，不会作为弹幕广播。

一个常用汉字通常占三个 UTF-8 字节；完整 JSON 还包含字段名、ID 和时间戳。只检查正文字数会漏掉这些开销，先查总大小还能尽早拒绝超限输入。业务上限是 1,024 字节，底层容器另有接收缓冲边界。

预期拒绝通过 `ProtocolException` 携带原因码，由 `reject()` 生成响应，再经广播器的统一回复入口发送。能识别为 send 的请求返回 `send_result/rejected`；解析前超限、非法 JSON 等返回通用 `error`。超出容器限制时也可能直接关闭连接。

## 5. 连接与成员关系，以及换房清理

`ClientConnection` 是一条真实连接的包装，内部持有 `WebSocketSession`。`Membership` 表示它当前属于哪个房间、显示什么昵称。连接刚建立时可以还没有成员关系；每条连接最多加入一个房间。

`ConnectionRegistry` 的四张 Map 分别支持：按连接 ID 找连接、按连接找成员关系、按房间名找 Room、按 Room 找该房间全部成员。

换房顺序是“检查目标是否允许加入 -> 删除旧成员关系 -> 建立新关系”。如果先离开旧房间，随后发现房间数量已满，就会让一次失败请求错误地把用户踢出原房间。

最后一个人离开时，删除内层成员记录、空的房间成员表、房间名到 Room 的记录。若只是换房，保留连接登记；若是断开，再删除连接登记。同名房间以后重新创建会使用新的 `roomInstanceId`，序号重新开始。

## 6. 为什么 JSON 生成一次，accepted 又意味着什么

一次广播给不同观众的弹幕内容相同，因此 `DirectBroadcaster` 先把事件编码成一份 JSON/TextMessage，再复用它逐个发送。10 个观众需要 10 次网络发送，但只需要 1 次 JSON 序列化，减少重复计算与临时对象。

`accepted` 仅表示服务端通过准入，当前基线准备直接广播。发送调用成功表示服务端发送路径正常返回。观众实际收到，还要等数据经过传输层并被客户端解析。三者不可互相替代。

例如，发送者收到 accepted 后某个观众立即断网，仍可能收不到这条消息。当前没有观众逐条 ACK 或可靠补发承诺。

## 7. 为什么弹幕和 pong 共用同一把发送锁

同一连接可能被广播线程发送弹幕，同时被心跳处理路径发送 pong。标准底层 Session 不应被应用并发发送，因此所有应用消息统一进入 `ClientConnection.send()`。

该方法上的 synchronized 锁住当前 ClientConnection 对象。同一连接的弹幕、加入响应、发送结果和 pong 逐个写入；不同连接各自拥有锁。只给弹幕加锁而让 pong 绕开，仍会留下并发写入路径。

这把锁保证单连接发送串行，不保证客户端已经收到，也不解决慢连接导致的等待。房间锁保护整轮广播顺序，Registry 锁保护多张 Map 的关系更新，三者职责不同。

## 8. 怎样证明观众真的收到了

`WebSocketIntegrationTest` 启动真实 Tomcat，使用 JDK WebSocket 客户端连接。客户端 Listener 收到完整文本后解析 JSON，放入收件箱；测试从每位观众收件箱取 danmaku，断言正文、关联 ID、序号等。这验证到了客户端解析层。

`scripts/load/smoke.js` 同样在每个客户端的 message 事件中统计：只数 danmaku，逐观众去重并检查序号，另一个房间的探针检查串房。全部 joined 后才发，最后一条发出后等待有限排空，再结算。accepted、pong、错误等都不算弹幕接收。

短轮操作：保留服务运行，另开 PowerShell：

```powershell
Set-Location D:\coding\DanmakuLab
New-Item -ItemType Directory -Force load-results | Out-Null
k6 run --quiet -e MESSAGES=3 scripts/load/smoke.js
```

应看到 joined=11、accepted=3、received=30/30、errors=0、duplicates=0、isolationFailures=0，退出码为 0。原因是 10 个同房间观众 × 3 条消息 = 30 次预期投递，另 1 条连接是隔离探针。

脚本也逐观众检查收到 3 条，避免只看总数掩盖某人漏收。完整默认轮不传 MESSAGES，共 60 条、600 次投递。小规模通过只说明这些场景正确，不外推千连接性能。

## 9. 把路径串起来

页面点击发送 -> WebSocket 文本消息 -> DanmakuHandler 校验与限流 -> Registry 查成员关系 -> DirectBroadcaster 取快照、生成序号和 JSON -> ClientConnection 逐连接串行发送 -> 观众 message 回调解析并展示。

第一遍可以只跟踪这条路径。收到一条消息后，再回到相应方法读紧邻的中文注释。
