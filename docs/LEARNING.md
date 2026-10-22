# 第一次协作：沿一条弹幕读代码

本阶段目标：自己启动、完成双页面演示，并能把消息路径讲清楚。无需先学完全部并发和网络课程。

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

这些问题是下一阶段慢消费者实验的起点。目前没有相关实测结论。
