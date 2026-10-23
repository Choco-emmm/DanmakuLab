# DanmakuLab

面向 Java 后端学习与简历展示的实时弹幕系统，重点研究大量连接下的消息广播、慢消费者处理、热点房间隔离和性能验证。

当前状态：已实现首版直接广播服务、演示网页和协议/资源边界测试。小规模冒烟及约 5 条/秒下的 100/500/1000 连接首轮基线已通过；最大容量、慢消费者优化和热点隔离仍待验证。

- [制作计划与每日任务](docs/PLAN.md)
- [架构与消息语义](docs/ARCHITECTURE.md)
- [压测设计与结果记录规范](docs/BENCHMARK.md)
- [当前协议与实现边界](docs/PROTOCOL.md)
- [第一次协作的代码阅读与练习](docs/LEARNING.md)
- [提交与推送检查清单](docs/RELEASE_CHECKLIST.md)

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

## 计数冒烟

保持服务运行，在另一个终端执行（已验证 k6 v2.0.0-rc1）：

```powershell
New-Item -ItemType Directory -Force load-results | Out-Null
k6 run scripts/load/smoke.js
```

10 个同房间观众，额外 1 个其他房间探针；全部加入后以约 1 条/秒发送 60 条，预期 600 次唯一接收。脚本检查每个观众的接收数、拒绝/错误、重复、异常断连和串房；约 61 秒完成，失败时 k6 返回非零退出码。
这只是工具和业务计数校验，不是容量压测。结果保存在被 Git 忽略的 `load-results/smoke-summary.json`；正式结果按 `docs/BENCHMARK.md` 归档。
首日已归档结果见 [T0 记录](docs/results/2026-10-22/archived-smoke/notes.md)。

快速检查可执行 `k6 run -e MESSAGES=3 scripts/load/smoke.js`。更换服务地址使用 `-e WS_URL=ws://127.0.0.1:8081/ws`。

Windows PowerShell 7 下可用 `pwsh -File scripts/run/smoke.ps1`，自动创建独立结果目录，保留环境、参数、源码 SHA-256、k6 输出和服务端前后指标。服务 JVM 参数和网络放置需补记到结果说明；脚本不会替你启动服务。

## 连接规模基线

通过冒烟后，按 [T1 操作说明](docs/BASELINE.md) 使用 `scripts/load/baseline.js` 和 `scripts/run/baseline.ps1` 测量 100、500、1000 条连接。每档默认预热 60 秒、测量 180 秒，总输入 5 条/秒，保存客户端接收、抽样延迟、双端资源时序和服务端计数差值。
完整输入 JSON 为 256 字节，广播 JSON 大小另行实测；这些参数不等于每秒 20 条、慢消费者或 30 分钟稳态验收。
首轮实测见 [T1 结果报告](docs/results/2026-10-23/t1-report.md)：三档测量期均收齐，抽样 P95 为 4/14/23ms。每档目前只有一轮，同机回环，不能外推为最大容量或公网成绩。

## 当前实现与下一阶段

真实慢消费者的首次小规模复现已完成：[操作说明](docs/SLOW_CONSUMER.md)、[对照结果](docs/results/2026-10-23/slow-report.md)。停止一个探针的 TCP 读取后捕获同步发送等待及 Room 锁竞争；有界缓冲优化尚未实现。

已具备房间加入/切换、直接广播、房间实例序号、连接清理、输入约束、连接/房间弹幕限流、应用心跳、浏览器退避重连和基础指标。页面仅以文本渲染弹幕，并限制保留的 DOM 数量。

基线逐连接同步发送，慢连接仍可能拖慢分发。100/500/1000 连接首轮和小规模停读机制实验已完成；下一步核查实际发送超时，再引入有界发送缓冲、过载策略和批次调度。Redis 双实例属于 P1。

目标：北京时间 **2026 年 10 月 29 日 23:59 前**完成核心交付，10 月 30 日用于缓冲和演示。

项目最终需要交付：可运行的服务和演示网页、可复现的压测脚本、优化前后对比报告，以及有实测依据的简历描述。
