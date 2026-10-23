# 首次真实慢消费者机制复现

日期：2026-10-23。本实验已复现直接广播同步发送等待，并观察到同房间正常观众长尾延迟显著上升。
仅为 11 连接、90 秒的小规模机制实验，不代表计划中的正式 T3（1000 连接、1% 慢观众、15 分钟）。

## 条件

独立服务 127.0.0.1:8081，Java PID 7548，JDK 25.0.1，JVM -Xms128m -Xmx256m，原直接广播 JAR。
没有修改后端或启用虚拟线程。Python 3.14 标准库实现本地 WebSocket 客户端，10 个正常观众加一个先入房的探针。
每秒 40 条输入，正常观众轮流发送，单连接目标 4 条/秒；正文 200 个汉字，600 UTF-8 字节，不与 T1 的 256 字节输入混比。
两组探针均设置 SO_RCVBUF，系统查询值为 1024 字节。正常组持续读取；慢组收到 joined 后不再 recv，但仍尝试每 10 秒发送应用心跳。
每轮计划 3600 条输入，正常观众预期投递 36,000 次；停止发送后等 7 秒，全部正常观众仍保留在分母中。

## 结果

| 指标 | 正常组 | 停止读取组（复测） |
| --- | --- | --- |
| 输入消息 | 3600 | 3600 |
| 接受 | 3600 | 2849 |
| 拒绝 | 0 | 751，均为 connection_rate_limit |
| 正常观众接收 / 全部输入投递需求 | 36,000 / 36,000 | 28,490 / 36,000 |
| 正常观众收到的已接受消息 | 36,000 / 36,000 | 28,490 / 28,490 |
| 正常观众延迟 P50 / P95 / P99 ms | 1 / 1 / 1 | 1 / 1 / 19,362 |
| 最大延迟 ms | 4 | 20,053 |
| 重复 / 乱序 | 0 / 0 | 0 / 0 |

慢组约第 5、10、15、20 秒的采样均为 accepted=74、normalReceived=730；第 25 秒开始恢复。
探针约第 30 秒的心跳写出现 ConnectionResetError，已被服务端断开。该异常保留在 failures，不删除正常观众来美化结果。
751 条拒绝不能算成已接受消息的网络丢失，也不能隐去：它们对应全部输入需求中的 7510 次未投递。积压后请求的处理时机变化，连接令牌桶在处理时校验，恢复期间出现拒绝。
P95 仍为 1ms，P99 却接近 20 秒，因此单看平均值或 P95 会掩盖短时严重停顿。延迟只统计实际收到的消息，必须连同拒绝和输入需求一起读。

## 发送阻塞证据

首轮的 [线程栈](slow-paused-first/threads-during.txt) 捕获同一条调用链：

```text
SocketWrapperBase.vectoredOperation / write
WsRemoteEndpointImplBase.sendMessageBlockInternal
StandardWebSocketSession.sendTextMessage
ClientConnection.send (第 46 行，持有 ClientConnection 锁)
DirectBroadcaster.broadcast (第 72 行，持有 Room 锁)
```

另有多个线程 BLOCKED 在 DirectBroadcaster.broadcast 第 45 行，等待同一个 Room 对象锁。
这与客户端接收计数平台相互支持，说明真正发生了同步发送等待和房间广播竞争，而不是仅延迟 UI 展示。
当前观测到约 20 秒延迟，不能把代码里设置的 5 秒发送超时解释为已验证的实际等待上限；Session 属性传递与容器超时路径需另行核查。

## 归档与工具限制

- [正常组汇总](slow-normal-first/summary.json)、[参数](slow-normal-first/config.json)。
- [慢组复测汇总](slow-paused-retry/summary.json)、[参数](slow-paused-retry/config.json)、[线程栈](slow-paused-retry/threads-during.txt)。
- [原 JAR 与脚本哈希](slow-paused-retry/hashes.json)、[JVM 命令](slow-paused-retry/jvm-command.txt)。
- [首轮工具提前退出说明](slow-paused-first/notes.md)：心跳写失败未捕获，未生成完整汇总；保留而不覆盖。
- 正常组用初版脚本，慢组复测仅增加心跳写失败的记录与继续收尾行为，没有修改正常发送计划、正文、接收计数和网络停读机制。两组不是完全相同脚本哈希，后续正式对照必须固定修订后版本重新运行两组。
- server-after.json 为复用进程的累计值，包含之前实验，不作为本轮独立增量。尚未采集本轮双端资源时序，因此不声称本实验验证内存或 CPU 上限。
- 每组只有一轮有效结果、同机回环、无预热；只用于指导下一阶段，不作为最终容量或优化成绩。

下一步：先核查超时配置实际传递路径，再设计连接有界发送缓冲及过载断连；用固定版本重复正常/慢组，验证正常观众受到的影响是否降低。
