# 第一次协作：沿一条弹幕读代码

本阶段目标：自己启动、完成双页面演示，并能把消息路径讲清楚。无需先学完全部并发和网络课程。

第一次使用先看 [逐步演示与六个问题的解释](WALKTHROUGH.md)，照着完成双页面收发后，再按下文阅读源码。

阅读中提出的问题按主题记在 [学习问答：令牌桶、Java 语法与测试](LEARNING_QA.md)，包含限流范围、额度恢复公式、`lastRefill`、`LongSupplier` 和模拟时钟测试。

查看 [类之间的调用关系](CLASS_RELATIONSHIPS.md)，可以沿启动、建连、加入房间、限流、广播与断开清理，对照理解截图中的各个类。

## 建议阅读顺序

1. `config/WebSocketConfiguration`：为什么访问 `/ws` 会进入处理器？为什么网页由同一个服务提供？
2. `protocol/DanmakuHandler`：如何区分 join/send/ping？为什么先检查完整 UTF-8 字节数？拒绝原因在哪里返回？
3. `core/ConnectionRegistry`：连接与房间成员是什么关系？换房失败为什么不能提前删旧成员？最后一个人离开后清理什么？
4. `core/DirectBroadcaster`：为什么生成一次 JSON 再发送给所有人？`accepted` 和实际收到有什么区别？
5. `core/ClientConnection`：弹幕和 pong 为什么需要同一把发送锁？
6. `WebSocketIntegrationTest` 和 `scripts/load/smoke.js`：如何证明观众确实收到，而不仅是服务器调用过 send？

这些 Java 类位于 `src/main/java/io/github/chocoemmm/danmakulab/`；集成测试位于 `src/test/java/io/github/chocoemmm/danmakulab/`。

## 亲手做一次

1. 执行 README 的构建与启动命令，打开两个页面，加入 `lobby`，双向发消息。
2. 把其中一个页面切到 `another`，验证不同房间不再互相看到新弹幕。
3. 执行 k6 冒烟，解释为什么 60 条输入对应 600 次预期投递，为什么实际有 11 个连接。
4. 读完 `ClientConnection.send`，用自己的话描述去掉同步后，两条发送路径可能怎样交错。暂不改动正式基线。

## 下一次讨论的三个问题

- 如果一个观众一直不接收，当前广播循环会卡在哪里？其他房间是否也可能受影响？
- 如果把每次发送都丢进线程池，需要限制哪些资源？
- 假如我们选择丢弃旧普通弹幕，怎样让报告区分主动丢弃、网络断连和未知缺失？

这些问题是慢消费者实验的起点。目前已完成 [旧基线停读机制复现](results/2026-10-23/slow-report.md)，接入 [有界输出](BOUNDED_OUTBOUND.md)与可选 [房间入口/轮转](ROOM_DISPATCH.md)；正式热点与长期验收仍待完成。

## 10 月 26 日从这里接着看

用户已完成 30 秒阶段的 fair 手动复测，10 月 25 日收尾后不再安排当晚代码学习。明天先沿以下路径阅读，不必重新通读整个项目：

1. `FairRoomDispatcher.submit`：怎样检查容量，什么时候分配序号，accepted 表示什么。
2. `dispatchOneTurn`：怎样保留观众遍历位置，什么时候把房间放回队尾。
3. `DirectBroadcaster.deliver → ClientConnection.send → OutboundMailbox.drain`：房间调度怎样交给连接邮箱，哪一步才写网络。
4. `FairRoomDispatcherTest`：用轮转轨迹、序号和预算断言核对上面的理解。

今日完成与未完成事项见 [收尾交接](results/2026-10-25/day4-wrapup.md)。

10月26日后，buffered的实际网络写已改为`drain → ClientConnection.writeAsync → OutboundMailbox.finish`，direct保留同步基线。先看 [混合预检为什么失败与异步修复](ASYNC_OUTBOUND.md)：提交返回、完成回调和客户端收到是三个时刻；预算在回调完成时才释放。

今天已整理 [一条弹幕的完整代码阅读路线](MESSAGE_PATH.md)，用A/B/C三位观众解释房间输入队列、每连接邮箱、共享提交线程和回调。先按里面的方法名跳转，再看 [T2工具如何证明实际接收](THROUGHPUT.md)；正式四档结果和限制见 [T2首轮报告](results/2026-10-26/t2-report.md)。

同日已完成 [T6重连验证](RECONNECT.md)：100/500人各三轮，一半主动断开并重新join，另一半继续收齐。读Java时接着看`ConnectionRegistry.register/remove → ClientConnection.retireIfFinished`：为什么旧连接关闭与在途写都完成后，名额才能供新连接复用。首次重连成功与多轮失败退避的覆盖不同，详见 [T6报告](results/2026-10-26/t6-report.md)。
