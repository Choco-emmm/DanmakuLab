# 慢消费者：先复现，再优化

目的：验证一个停止网络读取的观众，是否让直接广播卡在同步发送，并拖慢同房间正常观众。
本实验是小规模机制复现，不替代计划中的 1000 连接、1% 慢观众、15 分钟正式验收。

## 运行方法

需要 Python 3，使用标准库，无需 pip 安装。保持测试服务 8081 以默认 direct 模式运行，配置与 T1 相同；buffered 的双服务对照入口见 [有界输出说明](BOUNDED_OUTBOUND.md)。
在项目目录、另一个终端依次执行；每轮约 97 秒，目录名称必须尚不存在。

```powershell
cd D:\coding\DanmakuLab
python scripts/load/slow-consumer.py --mode normal --seconds 90 --output load-results/slow-normal-01
python scripts/load/slow-consumer.py --mode paused --seconds 90 --output load-results/slow-paused-01
```

每组有 10 个正常观众加 1 个先入房的探针，输入合计 40 条/秒，正常连接轮流发送。
正文固定 200 个汉字、600 UTF-8 字节，完整输入帧仍受服务端 1024 字节限制。
两组探针都设置 SO_RCVBUF=1024，并记录系统返回的实际值；normal 组继续读取，paused 组收到 joined 后不再调用 recv。
客户端仍发送应用心跳；因此不是简单等待 60 秒空闲断开。TCP 接收缓冲仍然会先接收有限数据，不承诺在停止读取的瞬间阻塞服务端。

脚本自行实现本实验所需的 RFC 6455 握手、客户端掩码和文本帧读取，只允许本地 ws 地址，不是通用 WebSocket SDK。
Python 线程用于持续读取正常连接；发送加锁，统计使用独立锁。每轮最多 900 秒、40 条/秒、100 个正常观众，去重集合及延迟样本都受本轮配置限制。
不要把这个 Python 小规模实验的延迟直接与 k6 T1 相比：消息大小、输入速率、客户端实现和采样方式均不同。

## 如何读结果

控制台每 5 秒输出 sent、accepted、normalReceived、probeReceived，最终写 summary.json。
normal 组探针和普通观众都应收到消息；paused 组 probeReceived=0 是工具刻意不读，单凭这一点不能证明服务器阻塞。
观察 normalReceived 是否出现长时间平台、正常观众延迟是否上升；结合线程栈判断发送是否真的等待。
报告中的 normalExpected 始终包含所有正常观众，没有删除断开的观众来提高完整率。
心跳写失败和接收异常写入 failures；正常发送者写失败会停止输入并报告 inputScheduleCompleted=false。
汇总分别记录 plannedInputMessages（计划）、attemptedInputMessages（尝试）与 submittedInputMessages（发送调用正常返回）；inputMessages 使用成功提交计数，仍不等于服务端接受或观众收到。
初始化建连、握手和 joined 共享 3 秒总预算，失败时关闭尚未登记的 socket；这与后端发送超时是不同方向的限制。
进程正常退出只表示实验收尾完成，不代表功能或性能全部通过；必须检查接收缺口、失败、乱序和输入是否完成。

工具失败路径回归使用 Python 标准库 unittest，无需启动 Java：

```powershell
python -m unittest discover -s scripts/tests -v
```

如需抓线程栈，先从服务启动日志找 Java PID，然后在第三个终端执行：

```powershell
jcmd <Java进程PID> Thread.print > load-results/slow-threads.txt
```

把 `<Java进程PID>` 替换成实际数字。线程栈重点搜索：

```text
SocketWrapperBase.write / WsRemoteEndpointImplBase.sendMessageBlockInternal
ClientConnection.writeNow / ClientConnection.send
DirectBroadcaster.broadcast
locked ... Room
waiting to lock ... Room
```

前一条调用链结合持有 Room 锁，说明正在同步发送路径里等待；其他线程等待同一 Room 锁，说明房间广播被连带阻塞。
单次线程栈仅是某一时刻的证据，不能由此推出整段等待时长或超时原因。2026-10-24 已确认旧配置没有传入 Tomcat 原生属性，并修正为有效 5 秒同步发送超时；详见 [诊断与当前实现](BOUNDED_OUTBOUND.md)。这个单次写等待限制仍不是端到端最长延迟。

## 当前结果与下一阶段

初步实验记录见 [2026-10-23 机制复现](results/2026-10-23/slow-report.md)；连接有界缓冲、统一单写、过载与回收已实现，并完成 [两轮同有效超时的四组对照](results/2026-10-24/outbound-report.md)。
房间入口与批次调度已实现，2026-10-25又完成正式热点对照、初始10慢读观众的15分钟观察及30分钟稳态，见 [新长测入口](LONGRUN.md)、[结果与限制](results/2026-10-25/formal-longrun-report.md)。8发送线程混合预检失败；显式32线程候选正常组收齐，慢观众被断开后未补建，不代表全程恒定1%慢连接。后续先解决默认配置和共享发送池边界，再重复关键对照，见 [待办](../TODOS.md)。

上述为旧JAR的历史记录。2026-10-26已将buffered改为容器异步写，默认8提交线程的千连接初始1%慢读15分钟正式T3收齐正常组17820000次，抽样P99=35ms；见 [异步实现](ASYNC_OUTBOUND.md)、[修复与复测](results/2026-10-26/async-writer-report.md)。不移用旧版30分钟T5成绩，重复与新完整矩阵仍待完成。
后续对照继续固定脚本、消息、速率和环境，不同时切换虚拟线程，避免无法归因。
