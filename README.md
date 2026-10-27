# DanmakuLab

面向 Java 后端学习与简历展示的实时弹幕系统，重点研究大量连接下的消息广播、慢消费者处理、热点房间隔离和性能验证。

新增可选 [Redis双节点](docs/CLUSTER.md)：首轮制品100/1000正常观众60秒分别收齐120000/1200000次，抽样P99=7/40ms；Redis中断、真实发布超时unknown、TCP慢读和节点退出重连通过，见 [报告](docs/results/2026-10-26/redis-report.md)。默认local保留，限流/序号按节点计量，不承诺容量翻倍、可靠补发或全局严格顺序。单机65项基线回归与旧JAR已长期本地归档。

归档版本`0.3.0.0`的实现、工具和证据已提交并推送至`codex/hotspot-baseline`，尚未合入main。最终候选通过82项普通Java、1项真实Redis集成、44项Python测试，以及双节点4/4次边界投递和10观众600/600次冒烟；本轮自建资源已清理，见 [发布检查与制品哈希](docs/results/2026-10-27/ship-verification/README.md)。首轮规模和故障成绩仍绑定各自原制品，未冒充最终候选已重复全部长测。

当前状态：已实现直接广播、有界连接邮箱、可选房间入口与批次轮转及演示网页。2026-10-26将buffered改为容器异步写，修复多个慢连接占满共享发送池；保留默认8个提交线程，千连接、初始1%慢读、15分钟正式T3中正常组收齐1782万次，抽样P99=35ms，见 [修复报告](docs/results/2026-10-26/async-writer-report.md)。
用户又完成同一新版JAR、默认8提交线程的 [30分钟正式T5](docs/results/2026-10-26/t5-094730/notes.md)：1000正常连接收齐3600万次测量投递，抽样P95/P99=28/35ms，计数与清理通过。新版 [正式T4主对照](docs/results/2026-10-26/async-t4-report.md)也已完成：两种房间调度各收齐11544500次，普通房间P99均未超过32.01ms；修复一次指标采集连接问题并保留失败轮，未证明fair全面更快。旧版8线程失败及临时32线程成绩保留在 [历史报告](docs/results/2026-10-25/formal-longrun-report.md)。

[T2首轮正式阶梯](docs/results/2026-10-26/t2-report.md)随后完成：固定1000观众、四个客户端进程、显式200/s房间额度，5/20/50/100条输入每秒各测180秒，测量期3150万次全收，P99=9/9/9/51ms。此次未改Java，不能把与旧压测器的延迟差当作服务端优化收益。

[T6首轮重连](docs/results/2026-10-26/t6-report.md)也已完成：100/500人各三轮、一半断连重入，必达110000/550000次全部收到，全员每轮约1.03秒内恢复；真实满额拒绝与资源清理通过。Python模拟页面退避规则，未证明浏览器或网络黑洞场景。关键对照三次重复、最大容量与完整P0交付仍待验证；默认输出模式仍为direct，需显式开启buffered。

- [制作计划与每日任务](docs/PLAN.md)
- [架构与消息语义](docs/ARCHITECTURE.md)
- [压测设计与结果记录规范](docs/BENCHMARK.md)
- [当前协议与实现边界](docs/PROTOCOL.md)
- [第一次协作的代码阅读与练习](docs/LEARNING.md)
- [提交与推送检查清单](docs/RELEASE_CHECKLIST.md)
- [有界输出邮箱与慢连接对照](docs/BOUNDED_OUTBOUND.md)
- [房间入口队列与批次调度](docs/ROOM_DISPATCH.md)
- [正式长测操作与统计口径](docs/LONGRUN.md)
- [慢连接为什么连带正常观众，以及异步修复](docs/ASYNC_OUTBOUND.md)
- [一条弹幕怎样经过两层队列与完成回调](docs/MESSAGE_PATH.md)
- [T2单房间吞吐阶梯操作与口径](docs/THROUGHPUT.md)
- [T6突发断连与退避重连操作](docs/RECONNECT.md)
- [Redis双节点运行、消息身份与失败语义](docs/CLUSTER.md)
- [提交前改动的审查、修复和分组记录](docs/results/2026-10-26/prepublish-review/README.md)
- [归档版本变更记录](CHANGELOG.md)、[v0.2.0.0 提交前验证](docs/results/2026-10-24/ship-verification/notes.md)与[上一版发布检查记录](docs/results/2026-10-23/release-check.md)

## 本地启动

已验证环境：Windows 11、Oracle JDK 25.0.1、Maven 3.9.12；Spring Boot 3.5.16 / Tomcat 10.1.55。源码使用 `--release 21` 编译，尚未在 Java 21 运行时验证。

```powershell
mvn -B -ntp verify
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar
```

打开 [演示页面](http://127.0.0.1:8080)，两个页面加入 `lobby` 后双向发送；将其中一个页面切换到 `another` 验证隔离。Ctrl+C 停止服务。
开发时也可使用 `mvn spring-boot:run`。
Windows 上重新打包前先停止已启动的 JAR，避免运行中的文件被占用。

默认只监听 `127.0.0.1:8080`，无需数据库、Redis 或 Docker。健康检查为 `/actuator/health`，指标入口为 `/actuator/metrics`。
修改端口示例：启动时增加 `--server.port=8081`；配置在 `src/main/resources/application.yml`。
默认输出模式为 `direct`；启动时增加 `--lab.outbound.mode=buffered` 才启用连接邮箱和共享发送池。两种模式的对照命令见 [有界输出说明](docs/BOUNDED_OUTBOUND.md)。
房间调度也默认 `direct`；同时增加 `--lab.dispatch.mode=fair --lab.outbound.mode=buffered` 启用房间入口与批次轮转。fair 禁止搭配直接网络输出，参数与测试见 [房间调度](docs/ROOM_DISPATCH.md)。
房间调度初版通过58项Java测试，千连接短轮各收齐664500次，见 [历史验证](docs/results/2026-10-25/room-dispatch-report.md)。后续异步输出版本已通过65项Java测试，新版正式T4见上方主对照；未宣称fair全面改善延迟。

## 计数冒烟

保持服务运行，在另一个终端执行（已验证 k6 v2.0.0-rc1）：

```powershell
New-Item -ItemType Directory -Force load-results | Out-Null
k6 run scripts/load/smoke.js
```

10 个同房间观众，额外 1 个其他房间探针；全部加入后以约 1 条/秒发送 60 条，预期 600 次唯一接收。脚本检查每个观众的接收数、拒绝/错误、重复、异常断连和串房；约 61 秒完成，失败时 k6 返回非零退出码。
这只是工具和业务计数校验，不是容量压测。结果保存在被 Git 忽略的 `load-results/smoke-summary.json`；正式结果按 `docs/BENCHMARK.md` 归档。
首日已归档结果见 [T0 记录](docs/results/2026-10-22/archived-smoke/notes.md)，工具兼容性失败及修正见[首轮记录](docs/results/2026-10-22/first-smoke/notes.md)。

快速检查可执行 `k6 run -e MESSAGES=3 scripts/load/smoke.js`。更换服务地址使用 `-e WS_URL=ws://127.0.0.1:8081/ws`。

Windows PowerShell 7 下可用 `pwsh -File scripts/run/smoke.ps1`，自动创建独立结果目录，保留环境、参数、源码 SHA-256、k6 输出和服务端前后指标。服务 JVM 参数和网络放置需补记到结果说明；脚本不会替你启动服务。

## 连接规模基线

通过冒烟后，按 [T1 操作说明](docs/BASELINE.md) 使用 `scripts/load/baseline.js` 和 `scripts/run/baseline.ps1` 测量 100、500、1000 条连接。每档默认预热 60 秒、测量 180 秒，总输入 5 条/秒，保存客户端接收、抽样延迟、双端资源时序和服务端计数差值。
完整输入 JSON 为 256 字节，广播 JSON 大小另行实测；这些参数不等于每秒 20 条、慢消费者或 30 分钟稳态验收。
首轮实测见 [T1 结果报告](docs/results/2026-10-23/t1-report.md)：三档测量期均收齐，抽样 P95 为 4/14/23ms。每档目前只有一轮，同机回环，不能外推为最大容量或公网成绩。
另有独立保存的 [100 连接手动复跑](docs/results/2026-10-23/t1-100-162328/notes.md)，未覆盖首轮结果。

## 当前实现与下一阶段

已开始 [热点房间基线](docs/HOTSPOT.md)：分房间/阶段统计输入、拒绝与真实接收，已完成 [1000 连接初步短轮](docs/results/2026-10-24/hotspot-initial-report.md)。早期尾延迟与工具调度延后同时出现，不能直接归因为服务端。

已补 `-SplitProcesses` 将普通/热点房间分配给两个独立 k6 进程，并对齐四个输入阶段。[拆分千连接短轮](docs/results/2026-10-25/hotspot-split-report.md)收齐 664000 次投递；普通房间此前数百毫秒的尾延迟未在本轮重现。服务端未改动，仍需重复与更长窗口验证。

真实慢消费者的首次小规模复现已完成：[操作说明](docs/SLOW_CONSUMER.md)、[旧基线结果](docs/results/2026-10-23/slow-report.md)。停止一个探针的 TCP 读取后捕获同步发送等待及 Room 锁竞争；新增的 [有界输出模式](docs/BOUNDED_OUTBOUND.md)通过独立对照继续验证效果。

已具备房间加入/切换、直接广播、房间实例序号、连接清理、输入约束、连接/房间弹幕限流、应用心跳、浏览器退避重连和基础指标。页面仅以文本渲染弹幕，并限制保留的 DOM 数量。

基线逐连接同步发送，慢连接仍可能拖慢分发。已修正Tomcat原生发送超时并实现有界输出、可选房间入口、全局输入预算和观众批次轮转；新版buffered用原生异步完成回调释放预算、恢复提交，默认8线程混合慢读正式T3已通过一次。T2～T6及Redis双节点均已有首轮记录；关键独立重复和完整P0交付仍待完成，双节点结果不替代单机验收。
当天 [首轮输出对照](docs/results/2026-10-24/outbound-report.md)中，同有效超时的停读场景 direct 正常观众 P99=4216ms，buffered P99=1ms 且收齐 36,000 次；仅为 11 连接、单轮 90 秒机制实验。

[用户独立复测](docs/results/2026-10-24/slow-compare-205930/notes.md)同样完成四组对照；提交前补齐停机与失败日志路径后，最终 [32 项 Java 测试与制品冒烟](docs/results/2026-10-24/ship-verification/notes.md)通过。后续任务入口见 [TODOS](TODOS.md)。

目标：北京时间 **2026 年 10 月 29 日 23:59 前**完成核心交付，10 月 30 日用于缓冲和演示。

项目最终需要交付：可运行的服务和演示网页、可复现的压测脚本、优化前后对比报告，以及有实测依据的简历描述。
