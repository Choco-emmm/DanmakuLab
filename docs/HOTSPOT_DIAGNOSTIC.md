# 热点发送滞后与关闭阶段的短诊断

此模式补充T4的客户端与服务端时间线，用于调查发送滞后、接收落后与关闭拥堵的先后关系。默认关闭，只允许每阶段最多10秒；诊断轮不能计入三对正式验收。Java、application.yml、发布JAR与原有通过门槛均不由它修改。

## 固定运行入口

在项目根目录运行，结果目录必须换新名字。脚本自行创建实验JVM，默认占用8085；端口已有服务时拒绝启动，只清理自己启动的资源。

```powershell
pwsh -NoProfile -File scripts/run/diagnose-hotspot.ps1 -Suite smoke -OutputDirectory docs/results/2026-10-27/my-smoke
pwsh -NoProfile -File scripts/run/diagnose-hotspot.ps1 -Suite injected -OutputDirectory docs/results/2026-10-27/my-injected
pwsh -NoProfile -File scripts/run/diagnose-hotspot.ps1 -Suite compare -OutputDirectory docs/results/2026-10-27/my-compare
```

| Suite | 固定次数与用途 |
| --- | --- |
| smoke | 60连接、每阶段3秒，全关与每批10个各一次，检查真实收发和时间线 |
| injected | 60连接，每个k6进程显式停顿1.1秒一次，验证原有发送滞后门槛会失败 |
| compare | 1000连接、每阶段10秒，依次全关、每批25个、每批25个、全关；批间隔100ms |

每次都新建JVM，使用同一JAR、fair+buffered与默认限流。普通房间与热点分别使用独立k6进程，共用绝对阶段起点。运行前固定次数，不自动重试或替换失败轮。防自动休眠请求随实验释放；手动睡眠、重启仍会使数据无效。

调度器完成预定尝试时，不能只看外层进程退出码。看`suite-status.json`中每项的`loadPassed`和`evidenceComplete`；前者表示负载/计数/清理通过，后者表示失败或成功证据可审计。注入轮应当保留`loadPassed=false`，不能当作吞吐成绩。

## 沿时间线读一轮结果

每轮的`load/normal`与`load/hot`包含k6日志、汇总及退出状态。日志中的`T4_DIAGNOSTIC`事件依次记录：

1. `started`、`phase_started`：客户端启动、阶段切换；每秒另有一个累计`sample`。
2. `input_stopped`：停止新增发送，继续处理已发消息的回复与弹幕。
3. 有限排空后`closing_started`：开始请求关闭，可一次全关或按批请求。
4. `close_requested`、`first_socket_closed`、`first_close_completed`：本方请求与观察到的关闭分别计数，首个远端关闭保留code/reason；观察到关闭不代表消息已送达。
5. `settled`：全部关闭回调或有限关闭截止后，结算真实接收和首个错误。失败同样保留终态与原始退出码。

`maxObservedSendLagMs`是计划发送到实际发送tick的滞后；`maxTickDriftMs`表示10ms定时器的额外迟到。`acceptedDeliveryDemand - received`只是客户端在该时刻观察到的需求差，accepted回复也可能晚到，差值可能暂为负；它不等于服务端排队条数，不能截成0后称为精确积压。

服务端约每秒保存`samples.jsonl`：连接、房间、两层预算、在途写、线程和进程CPU/内存。每份快照保存起止时间，因为多个HTTP查询是顺序读取的，并非同一瞬间。缺测保留错误，不用0代替。客户端首个关闭请求之前已完整采完的快照，才可明确称为“关闭前”；跨越关闭时刻的快照不能强行归因。

## 独立检查

```powershell
python -X utf8 scripts/tests/hotspot-diagnostic-summary.py docs/results/2026-10-27/my-compare/all-01/load
python -X utf8 scripts/tests/hotspot-diagnostic-summary.py docs/results/2026-10-27/my-injected/stall-01/load --evidence-only
```

`--evidence-only`允许失败轮的证据审计退出0，但输出仍保留`passed=false`。严格通过还必须满足原来的实际速率范围、最大发送滞后小于1000ms、全员接收、无重复/串房/来源流倒序、服务端计数一致和清理归零。

每轮`counter-delta.json`保留客户端与服务器差值，`cleanup-gauges.json`逐项保存12项指标，未知值为null。子进程失败后先尽力归档这些文件，再抛原错误；无法采集时记录缺失原因，不能补造成功证据。需要判断整个固定四轮时，逐项检查，保留失败轮。

本次实际执行结果见[诊断报告](results/2026-10-27/t4-diagnostic-report.md)。短轮调查不替代T4正式重复、T3慢连接或T5稳态验收。

下一层工具隔离使用 [热点工作进程入口](HOTSPOT_WORKERS.md)：所有热点进程在同一个房间，合并独立发送确认与逐观众位图，不沿用各进程accepted乘局部人数。一次性关闭保持一致，默认只跑短验收。
