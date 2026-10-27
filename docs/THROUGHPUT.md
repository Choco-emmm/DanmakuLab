# T2：单房间吞吐阶梯

T2回答“观众数量不变，输入变密后会怎样”。T1改变连接数，T4同时有普通房间和热点房间，T2固定1000个观众在一个房间，逐档提高输入。四个客户端进程只是压测端分担接收，Java仍是单服务实例、单房间；没有引入分布式后端。

2026-10-26首轮正式阶梯已通过：测量期31500000次全收，四档P99=9/9/9/51ms，见 [结果、资源图和限制](results/2026-10-26/t2-report.md)。仍需重复，未证明最大容量。

| 输入 | 1000人房间的测量期理论投递需求 | 每档180秒的投递需求 |
| --- | --- | --- |
| 5条/秒 | 5000次/秒 | 900000次 |
| 20条/秒 | 20000次/秒 | 3600000次 |
| 50条/秒 | 50000次/秒 | 9000000次 |
| 100条/秒 | 100000次/秒 | 18000000次 |

只有输入全部接受、固定观众全部接收时，实际值才等于表中需求。这些是消息投递次数，不是HTTP QPS，也不是最大容量结论。

## 1. 为什么要单独设置房间限流

项目默认房间桶为50/s。若保持默认值尝试100/s，会先被限流挡住，不能据此判断100/s广播性能。因此T2独立服务显式使用200/s房间额度；每档都记录同一额度，不在测试中动态调参。单连接桶仍5/s，输入由第一分片的观众轮流发送，每位发送者目标不超过4/s。

此配置用于吞吐实验，不修改application.yml默认值，也不移用为T4的50/s结果。固定JAR，单独终端启动：

```powershell
cd D:\coding\DanmakuLab
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar --server.port=8085 --lab.outbound.mode=buffered --lab.dispatch.mode=fair --lab.room-messages-per-second=200
```

入口核对健康、实际模式Gauge、零连接以及进程命令中唯一的`--lab.room-messages-per-second=200`。命令行配置覆盖yml，服务器应按该值创建房间桶。没有明确启动参数时入口拒绝运行，不把yml的默认50/s当作200/s。该入口只采集同机回环。

## 2. 先短测，再正式运行

在另一个PowerShell 7.4以上终端：

```powershell
cd D:\coding\DanmakuLab
# 100连接、每档测3秒、预热1秒，先核对工具。
pwsh -NoProfile -File scripts/run/throughput.ps1 -Connections 100 -PhaseSeconds 3 -WarmupSeconds 1 -LatencySampleEvery 1

# 1000连接、每档测10秒、预热2秒，确认高档的压测端输入计划仍有效。
pwsh -NoProfile -File scripts/run/throughput.ps1 -PhaseSeconds 10 -WarmupSeconds 2

# 正式：1000连接，5/20/50/100条/秒；每档预热15秒、测量180秒，最后排空7秒。
pwsh -NoProfile -File scripts/run/throughput.ps1
```

正式轮约13分钟。四个进程各管理250个真实WebSocket连接，只有worker0的250位观众轮转发输入；所有发送者也算接收观众。等1000人全部join且房间实例一致后，父进程指定共同开始时刻。各档沿绝对计划运行，晚到消息仍按输入ID归属原来的预热或测量窗口。

四进程减少单一事件循环的压力，但它们仍和服务器共享CPU、内存与回环网络，不保证工具永远不饱和。默认每10个观众抽一个测延迟，完整率仍检查全部1000人。

## 3. 怎样证明实际收到

连接编解码复用既有Python原始TCP工具。每个进程独立校验收到的完整danmaku帧、房间/实例、运行标识、正文、时间戳、重复及递增序号；逐观众有界bytearray去重，四进程总去重表限制64MiB。

所有worker在结束后保存逐输入接收数、时间戳与序号，worker0另外保存逐输入真实发送时间及accepted/rejected。父进程逐条交叉核对：观众看到的时间戳必须与发送者记录一致，收到的弹幕必须对应accepted，同一输入的观众序号必须相同。接收分母始终包含初始全部观众，断开不会把该观众从分母删掉。

延迟用固定毫秒直方图，汇总时合并桶计数后再算总体P50/P95/P99，不平均四份P99。超过120秒的样本仍保留真实最大值、总和和溢出数量，不隐藏长尾。

## 4. 两种“通过”必须分开看

`client/summary.json`和`run-status.json`区分：

- `toolValid`：观众就绪、跨进程元数据、输入完成、确认完整、实际速率在目标±5%内、发送计划最迟不超过1000ms，且没有协议、重复或顺序错误。它只说明本轮负载与统计基本可信。
- `candidateSloPassed`：某档全部输入接受、预热/测量实际收齐、无读失败，且P95<200ms、P99<500ms、无延迟溢出。根目录还核对服务端计数、丢弃/写失败与清理。
- `allPhasesSloPassed`：四档均达到上述参考线，并且服务端证据匹配。工具有效但高速档不达标时，依然保存这一有效实验，不修改输入或观众数伪造全绿。

项目原定候选验收点是1000连接、20/s；50/100/s属于容量阶梯观察。高速档未达到参考线不等于20/s已经通过的稳态结果失效；工具本身达不到输入计划，也不能算服务器容量边界。

每档还保存`allInputDeliveryDemand`（全部计划输入×观众数）、`acceptedDeliveryDemand`（实际接受×观众数）、`received`、`missingAccepted`及拒绝原因，拒绝不能混成接受后漏收。预热拒绝与测量拒绝分开统计。

## 5. 归档与复核

默认结果目录是`docs/results/日期/t2-时间/`，已有目录拒绝覆盖。目录包含环境、启动命令、模式和额度、源码/JAR哈希、四个worker的报告/日志/进度、父进程退出状态、服务器前后及约五秒采样、计数差值和汇总。

HTTP指标整轮共享一个会话，减少监控自身的短连接积累；任何采样错误仍令入口失败。采样分别记录Java、Python父进程和四个worker的CPU累计秒、工作集、线程数。五秒采样最大值不是绝对峰值，输出载荷预算也不是总内存。复用同一JVM跑短轮和正式轮时，高水位仍是进程生命周期指标，不相减冒充本轮峰值。

工具离线测试和真实归档复核：

```powershell
python -m unittest discover -s scripts/tests -p test_throughput.py -v
python scripts/tests/throughput-summary.test.py docs/results/日期/本轮目录
# 仅正式参数增加此开关；不把短轮写成正式180秒。
python scripts/tests/throughput-summary.test.py docs/results/日期/本轮目录 --formal
```

入口：[Python客户端](../scripts/load/throughput.py)、[PowerShell采集器](../scripts/run/throughput.ps1)、[独立归档复核](../scripts/tests/throughput-summary.test.py)。代码学习可先看 [单条消息路径](MESSAGE_PATH.md)，再读本工具怎样把“观众实际收到”变成计数。
