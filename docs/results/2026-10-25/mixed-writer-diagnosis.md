# 多慢观众触发共享发送池边界

## 复现与最小化

正式长测前，新增 asyncio 工具先通过12项离线测试（含原慢读工具5项）和60正常连接真实收发。随后在默认8个发送线程、fair+buffered、1000连接（990正常+10限速recv）上运行30秒测量预检：

`pwsh -NoProfile -File scripts/run/longrun.ps1 -Scenario T3 -Connections 1000 -SlowConnections 10 -Seconds 30 -WarmupSeconds 5 -Rate 20 -OutputDirectory docs/results/2026-10-25/longrun-mixed-preflight-8writers`

正常观众收到154445/594000，990个正常读者收到关闭/读取错误，accepted只有257而发送调用返回700。断连后仍能短暂把字节写入TCP，不代表服务端继续接受业务输入，因此发送/接受/服务端输入也不再一致。失败结果完整保留，不能算15分钟验收。

把正常观众从990缩为10，保留10慢观众、20/s、256字节和8发送线程，仍能复现：20连接、20秒窗口，正常收到1564/4000；10个正常读者全部失败。输入计划滞后约16ms、正文/序号校验没有错误，降低了“千连接Python事件循环过载”的可能。

各目录：[千连接失败](longrun-mixed-preflight-8writers/run-status.json)、[20连接最小失败](longrun-mixed-minimal-8writers/run-status.json)。没有从正常观众分母删掉断开的连接。

## 检验的解释

按优先级检验：

1. 8个发送线程被至少8个慢Socket写占据，正常邮箱暂时得不到发送者。预测：只增加共享发送线程、保留控制TTL，正常读者恢复。
2. 1秒控制回复TTL本身过紧。预测：即使保留更多空闲发送者也会大量正常控制超龄；若只改线程数就恢复，则不能把TTL单独归为根因。
3. 客户端负载或统计错误。预测：缩为20连接会消失，或正文/序号、计划速率出现错误；实际最小复现仍失败。

时序记录到发送者全部活跃、输出排队增加，随后输出降级/关闭。按 reason 查询关闭指标，第一次千连接失败中的1000次关闭均为 `control_expired`，原响应保存在 [control-expired-evidence.json](longrun-mixed-preflight-8writers/control-expired-evidence.json)。

对应代码：`OutboundService` 只提供固定数量写线程；`OutboundMailbox.expire` 发现未发送的控制回复超龄后调用 `connection.fail("control_expired")`。限速观众并非断网，仍有心跳；读速小于入流，TCP缓冲逐渐形成背压。等待写的少数线程挡住了所有其他邮箱，控制回复不能静默丢弃，因此按现有策略关闭连接。

本次两份手动线程栈采得较晚，发送线程已回到池中等待，**不将它们当作捕获了阻塞写的正面证据**。结论来自复现、指标时序、原因码、代码路径及单变量对照，而不是凭空引用某帧栈。

## 只改发送线程数的对照

启动同一JAR、相同堆与TTL，只增加 `--lab.outbound.workers=32`，临时服务8084：

- [20连接对照](longrun-mixed-minimal-32writers/run-status.json)：正常4000/4000，P99=2ms，无正常断连。
- [1000连接原场景复查](longrun-mixed-preflight-32writers/run-status.json)：正常594000/594000，P95=32ms、P99=43ms，发送最晚滞后约70ms，无正常失败；服务端已观察到实际降级，回收通过。

这支持发送池被多个慢写占据是本轮正常观众连带失败的主要因素。它不是“任意比例慢观众均隔离”的证明；32个甚至更多慢写仍可能占满32线程。代价是更多共享平台线程和资源，长期表现仍需测量。

## 正式测试如何记录

不改默认配置，不重写8线程失败数据。正式 T4 的原/fair 调度仍同用8发送线程；T3/T5将使用显式32线程候选配置，并与默认配置成绩区分。测试时服务启动命令是有效参数的重要证据，不能只读归档的默认 application.yml。

后续900/1800秒结果以实际归档为准；当前短轮不冒充正式通过。未修改Java实现、控制TTL、房间/输出容量、输入速率或堆大小来掩盖失败。
