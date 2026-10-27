# 正式长测入口

本页描述执行方法，不预写通过结论。实际结果见每轮独立目录；长测授权只用于本地实验，不自动提交、推送或发布。

## 场景与时间

| 场景 | 默认负载 | 时长 |
| --- | --- | --- |
| T4 | 10个普通房间×50人、各2/s，热点500人、0/20/100/200/s | 每阶段180秒，加准备/排空约12分40秒；两种调度顺序运行约25分钟 |
| T3 | 1000连接，其中10个在预热后限速 recv 为1024B/s，输入20/s | 预热60秒、测量900秒、排空7秒 |
| T5 | 单房间1000正常连接，输入20/s | 预热60秒、测量1800秒、排空7秒 |

完整输入 JSON 为256个 UTF-8字节；实际广播增加服务端字段，大小不能直接当成256。T3/T5 使用新的 Python asyncio 客户端，T4仍用双 k6 进程，不能将不同工具的数据直接归为性能优化收益。

## 启动与执行

先按 [房间调度说明](ROOM_DISPATCH.md) 启动同一JAR的原调度8082、fair调度8083，输出都为 buffered。不要一边重打包、一边使用被占用的JAR；各轮负载顺序执行。

```powershell
cd D:\coding\DanmakuLab
pwsh -NoProfile -File scripts/run/hotspot.ps1 -SplitProcesses -PhaseSeconds 180 -DispatchMode direct -HttpUrl http://127.0.0.1:8082
pwsh -NoProfile -File scripts/run/hotspot.ps1 -SplitProcesses -PhaseSeconds 180 -DispatchMode fair -HttpUrl http://127.0.0.1:8083

```

以下32线程命令是2026-10-25旧版同步buffered的候选配置；当时8线程混合预检失败，见 [历史诊断](results/2026-10-25/mixed-writer-diagnosis.md)。保留其参数说明，不能将旧JAR结果移作新版成绩：

```powershell
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar --server.port=8084 --lab.outbound.mode=buffered --lab.dispatch.mode=fair --lab.outbound.workers=32
pwsh -NoProfile -File scripts/run/longrun.ps1 -Scenario T3 -Seconds 900 -Connections 1000 -SlowConnections 10 -HttpUrl http://127.0.0.1:8084
pwsh -NoProfile -File scripts/run/longrun.ps1 -Scenario T5 -Seconds 1800 -Connections 1000 -HttpUrl http://127.0.0.1:8084
```

新工具先用60连接、较短窗口验证握手、数据校验和汇总，再运行正式窗口。时长不足900/1800秒的短轮只算工具检查，`formalDurationSatisfied=false`，不能写成长期验收。

2026-10-25已完成本页所述一对正式T4及32线程候选T3/T5，见 [实际结果与资源曲线](results/2026-10-25/formal-longrun-report.md)。本轮服务默认参数未改，复现候选通过结果必须显式使用8084的32线程启动命令。

2026-10-26新版buffered改为异步提交，8线程正式T3已通过，见 [新修复报告](results/2026-10-26/async-writer-report.md)。测试当前代码使用默认8提交线程，不加workers=32：

```powershell
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar --server.port=8084 --lab.outbound.mode=buffered --lab.dispatch.mode=fair
pwsh -NoProfile -File scripts/run/longrun.ps1 -Scenario T3 -Seconds 900 -Connections 1000 -SlowConnections 10 -HttpUrl http://127.0.0.1:8084
```

新`lab.outbound.writes.inflight`指标也必须在测试收尾归零；复核工具仍兼容没有此指标的旧制品。

用户已手动完成同一新版JAR的30分钟正式T5，参数和数据见 [t5-094730](results/2026-10-26/t5-094730/notes.md)：36000000次测量投递全部收到，抽样P95/P99=28/35ms，正式复核与资源清理通过。这是当前8提交线程版本的成绩。

随后新版 [正式T4主对照](results/2026-10-26/async-t4-report.md)完成：两种房间调度各收齐11544500次，普通房间P99均不超过32.01ms，未证明fair全面更快。一次采集失败另存，修正hotspot.ps1复用指标HTTP连接后，两组重跑均通过采集、计数和清理。新版T3/T4/T5各首轮通过，不代替关键对照三次重复或完整P0。

## 为什么不直接扩大旧脚本

旧 Python 慢读脚本每个正常观众用一个读取线程，仅限100正常观众；旧 T1 脚本将一次测量限制在600秒。新工具每连接使用非阻塞原始socket，由一个asyncio事件循环驱动，不给1000连接创建1000线程。

慢观众在预热时正常读取，测量开始后在每次 `recv` 前按字节额度等待；不使用会后台预读的 StreamReader，接收内核缓冲设为1024并记录实际值。每次读取至多约八分之一秒额度，心跳继续正常发送。服务端可能丢弃或断开慢观众，必须另报收到比例、错误和服务端降级指标。

限速设置本身不是“已经压满传输层”的证明。正式 T3 还核对写失败或明确输出过期/过载，并结合服务端时序、慢组实际读取字节和必要的线程栈解释。

## 统计边界与检查

- 逐连接、逐输入去重的固定bytearray合计最多64MiB。1000连接、60秒预热、1800秒测量、20/s约37.2MiB，不无限存历史ID。
- 延迟按正常组每10个观众抽一个，慢组单独记录。固定毫秒直方图准确记录至120秒，超出计入末桶并保留溢出数/真实最大值；不得隐藏溢出或负延迟。
- 所有正常观众一直留在固定分母里；断开、工具读失败和发送计划失败会使本轮失败。慢观众按最初人数同时报告潜在投递分母，不能通过断开后删除它来美化比例。
- 接受确认逐输入去重；校验房间、实例、runId、正文、时间戳、观众间序号映射与递增顺序。发送者回执不能由其他观众冒充。
- 正常组要求完整接收、P95<200ms、P99<500ms；实际输入在目标±5%内，最大发送滞后小于1000ms。超过工具阈值必须保留失败结果，不能称目标负载达标。
- T3允许慢组被断开，但正常组仍需全收。T5全部正常，服务端expected/submitted必须与客户端含预热的总接收一致。

## 保存内容

`longrun.ps1` 复用 ProcessEvidence，保存 Python stdout/stderr/status、client/summary/config/timing/progress、源码/JAR哈希、环境与服务启动命令、服务端前后与每5秒资源采样、counter-delta和run-status。失败同样先归档，不能用进程退出0代替全部验收。

采样包括堆、GC、线程、双端CPU累计秒/RSS、输入/输出预算；采样最大值不是绝对峰值，逻辑载荷预算不是进程总内存。Windows同机回环共享资源，不外推公网或异机容量。关键对照仍需重复，单次正式长测不是三次重复结论。
