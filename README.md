# DanmakuLab

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg?style=flat-square)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.5.16](https://img.shields.io/badge/Spring%20Boot-3.5.16-brightgreen.svg?style=flat-square)](pom.xml)
[![WebSocket](https://img.shields.io/badge/WebSocket-native-blue.svg?style=flat-square)](docs/PROTOCOL.md)
[![Redis Pub/Sub](https://img.shields.io/badge/Redis-Pub%2FSub-red.svg?style=flat-square)](docs/CLUSTER.md)
[![k6](https://img.shields.io/badge/k6-v2.0.0--rc1-7D64FF.svg?style=flat-square)](docs/BENCHMARK.md)

一个用来“看见”消息怎么流动的实时弹幕实验项目：从 WebSocket 接入、房间广播、两层队列，到慢消费者隔离和客户端实收验证。

简单说，它研究一件事：**一条消息要发给很多人时，怎样不让慢的人拖垮快的人。**

面向 Java 后端学习与简历展示，适合想理解 WebSocket 广播、队列预算和压测口径的开发者。项目不只堆功能，更关注把 **广播扇出、慢消费者背压、热点房间隔离和性能验证** 做成可运行、可复现、可解释的实验。

- **技术栈：** Java 21（源码 `--release 21`）、Spring Boot 3.5.16、原生 WebSocket、Micrometer、Redis Pub/Sub（可选）、JUnit、k6、Python asyncio。
- **已验证规模：** 本机回环 1000 连接；T3 3×15 分钟、T5 3×30 分钟、T4 3 对 6 轮正式重复。
- **开箱即用：** 默认只监听 `127.0.0.1:8080`，不需要数据库、Redis 或 Docker；`java -jar` 即可打开演示页。

<p align="center">
  <img src="docs/assets/demo-page.png" alt="DanmakuLab 演示页：两个用户加入同一房间并互发弹幕" width="920">
</p>

<p align="center"><em>两个页面加入 <code>lobby</code> 后互发弹幕；右侧显示客户端实际接收计数。</em></p>

**快速导航：** [实测结果](#实测结果摘要) · [架构与消息路径](#架构与消息路径) · [快速开始](#快速开始) · [运行模式](#运行模式与关键配置) · [复现实验](#复现实验) · [测试与验证](#测试与验证) · [文档导航](#文档导航) · [已知边界](#已知边界与非目标) · [反馈与支持](#反馈与支持)

> **最近更新**
>
> - **2026-10-30：** 在 WSL2 Ubuntu / Temurin JDK 21 完成 11 连接、40 条输入/s、四组各 90 秒的同步/异步停读诊断。`ss` 与线程栈关联复现了“同步写持有房间锁”，同步停读组正常观众 P99 为 4322 ms；异步停读组正常观众 36000/36000 全收、P99 为 7 ms。见 [Linux 诊断报告](docs/results/2026-10-30/linux-io-01/notes.md) 与 [复跑说明](docs/LINUX_IO.md)。
> - **2026-10-28：** 最终候选 T3/T5 各三轮、T4 三对正式重复全部通过：T3/T5 测量期正常组累计 161,460,000 次全收；T4 六轮合计 69,267,000 次全收。见 [T3/T5 总结](docs/results/2026-10-28/longrun-repeat-02/notes.md) 与 [T4 逐轮报告](docs/results/2026-10-27/t4-workers-formal-01/notes.md)。

## 项目在解决什么问题

一条弹幕看起来只是“服务器把它发给房间里的人”，真正困难的是下面四件事同时成立。

| 问题 | 当前做法 | 怎么证明 |
| --- | --- | --- |
| 一条消息要发给同一房间的所有人 | 在房间锁内取成员快照、分配 `roomSeq`、整轮投递；发送者自己也计入分母 | 客户端逐观众按消息身份去重，完整率检查所有必达观众 |
| 一个慢连接不能拖住整个房间 | 可选 `buffered`：每连接有界邮箱、全局字节预算、每连接最多一条在途异步写 | 停读探针对照、服务端 `expected`/`enqueued`/`submitted` 与客户端实收对账 |
| 热点房间不能挤占普通房间 | 可选 `fair`：房间/全局入口预算、有限批次轮转 | T4 固定 direct/fair 三对重复，逐房间保留 P99，不平均后冒称整体 P99 |
| “服务端说成功”不等于“观众收到” | 把输入接受、进入队列、发送调用返回、容器写完成、客户端解析分别记录 | 所有延迟和完整率以客户端实际解析为准，服务端计数只用于对账 |

因此项目保留了两条基线路径（`direct` 输出、`direct` 房间调度）用于对照，而不是只展示“优化后的版本”。

## 核心能力

- **房间与协议：** `/ws` 文本 JSON；加入/换房、房间实例 `roomInstanceId`、服务端序号 `roomSeq`、应用心跳、稳定拒绝原因码。房间 ID、昵称、正文、完整帧大小都有上限。
- **接入控制：** 单连接与单房间两级令牌桶，允许一秒额度的突发；房间容量在换房时先检查新房间，失败则保留旧成员关系。
- **有界输出：** `buffered` 模式为每个连接维护独立 FIFO 邮箱，限制条数、字节和全局预算；普通弹幕超龄可淘汰，控制回复不能静默丢弃，无法保留时关闭连接。
- **异步发送：** 共享固定提交线程只发起容器异步写，不在线程里等网络完成；每连接最多一条在途，完成回调后释放预算并推进下一条。
- **房间调度：** `fair` 模式把待扇出的输入放进有界房间队列，按就绪房间轮转和批次预算分发，避免单个热点房间长期占住分发线程。
- **可选双节点：** Redis Pub/Sub 传播广播事件，每个 JVM 只持有自己的连接；来源节点身份、发布/接收有界队列、`unknown` 不自动重发。默认仍是 `local`。
- **可观测性：** Actuator `health`/`info`/`metrics`，以及连接、房间、输入/拒绝、投递、输出预算、调度队列和 Redis 桥接等 Micrometer 指标。
- **演示页：** 与 Spring Boot 同进程提供的原生 HTML/CSS/JS 页面；支持加入/换房、纯文本渲染、最多保留 200 条 DOM、15 秒应用心跳和带抖动的指数退避重连。

## 实测结果摘要

下列数字都绑定各自的 JAR、人数、输入速率、运行模式和机器条件。主力长测环境为 Windows 11 同机回环、Oracle JDK 25.0.1、Spring Boot 3.5.16 / Tomcat 10.1.55，源码以 `--release 21` 编译；通常使用 `-Xms128m -Xmx256m` 堆、完整输入 JSON 约 256 个 UTF-8 字节。Linux 组是独立的 WSL2/Temurin 21 小规模诊断。

| 场景 | 固定条件 | 客户端实收 | 延迟口径 | 关键限制 |
| --- | --- | ---: | --- | --- |
| [T1 连接基线](docs/results/2026-10-23/t1-report.md) | 100/500/1000 连接，5 条输入/s，180 秒 | 各档全收 | 抽样 P95 4/14/23 ms | 每档一轮，同机回环 |
| [T2 吞吐阶梯](docs/results/2026-10-26/t2-report.md) | 1000 观众，5/20/50/100 条输入/s，各 180 秒 | 3150 万次 | P99 9/9/9/51 ms | 显式房间额度 200/s，不是默认 50/s 的容量 |
| [T3 ×3](docs/results/2026-10-28/longrun-repeat-02/notes.md) | 990 正常 + 初始 10 慢读，20 条/s，900 秒 | 3 轮合计 5346 万次 | P99 31/30/29 ms | 慢组被关闭后不补建；不是零丢失 |
| [T5 ×3](docs/results/2026-10-28/longrun-repeat-02/notes.md) | 1000 正常观众，20 条/s，1800 秒 | 3 轮合计 1.08 亿次 | P99 36/39/37 ms | 每轮另有 60 秒预热，不计入测量数 |
| [T4 ×6](docs/results/2026-10-27/t4-workers-formal-01/notes.md) | 1000 观众，四阶段各 180 秒，direct/fair 三对 | 6926.7 万次 | 热点合并 P99 6–7 ms；普通房间单房 P99 最大 27 ms | 五热点进程；未证明 fair 全面更快 |
| [T6](docs/results/2026-10-26/t6-report.md) | 100/500 人，一半断连重入，3 个故障周期 | 必达 11 万/55 万次 | 恢复窗口 P99 3–4/15–16 ms | Python 模拟退避，不等于浏览器或网络黑洞 |
| [Redis 双节点](docs/results/2026-10-26/redis-report.md) | 100/1000 正常观众，60 秒，两 JVM | 12 万/120 万次 | 抽样 P99 7/40 ms | 首轮实网，不等于 30 分钟稳态或容量翻倍 |
| [Linux I/O](docs/results/2026-10-30/linux-io-01/notes.md) | 11 连接，40 条/s，四组各 90 秒 | 异步停读正常组 36000/36000 | 同步停读正常组 P99 4322 ms；异步 7 ms | 小规模机制诊断，不是千连接 Linux 容量 |

**可以带走的结论：**

- 最明确的收益是把共享发送线程从同步网络等待中释放。20 连接、10 个慢读、20 条/s、20 秒窗口的对照中，旧同步发送池正常观众只收到 1566/4000，改为异步输出后为 4000/4000；不能拿这个 20 连接场景去和 1000 连接长测混算提升倍数。
- `fair` 提供有界入口和批次轮转，但三对 T4 没有证明它在所有阶段都更快；普通房间与热点房间的逐房间 P99 必须分开看。
- T3 的慢组在形成背压后被保护性关闭且不补建，所以结论是“初始 1% 慢读条件下，正常组完整接收”，不是“全程 10 个慢观众且全场景零丢失”。
- Linux 组用 `ss`、线程栈和异步指标把“网络在等待”与“应用提交线程被占住”区分开；异步输出隔离了等待，但没有消除 TCP 背压。

**统计口径：**

- **实收**指客户端解析到真实 `danmaku` 后按消息身份去重计数；`accepted`、`enqueued`、`submitted` 都不能替代它。
- 千连接长测的 P99 对抽样连接计算，Linux 小规模组的 P99 覆盖本轮全部正常实收；**完整率始终检查全部必达观众**。多个房间/多轮的 P99 保留原值，不先平均再冒充整体 P99。
- 延迟从客户端记录的发送时刻（或实际发送时刻）到接收回调，包含客户端调度；发送计划滞后（`maxSendLagMs`）是另一项指标，不相加。
- 每轮使用独立 JVM、独立结果目录；失败轮、系统睡眠中断和先行失败都保留，不用后续成功轮覆盖。
- 换 JAR、换配置或换机器后，旧数字不能直接当作新版本的性能结论。

## 架构与消息路径

```text
浏览器 / k6 / Python 客户端
        │  ws://127.0.0.1:8080/ws（文本 JSON）
        ▼
DanmakuHandler ── 帧大小 / 字段 / 成员关系 / 两级令牌桶
        │
        ▼
BroadcastIngress
        ├─ local ──► 房间分发：direct（锁内直发）或 fair（有界队列 + 批次轮转）
        └─ redis ──► 有界发布队列 ──► Redis Pub/Sub ──► 订阅回流 ──► 房间分发（fair）
        │
        ▼
DirectBroadcaster：成员快照 + roomSeq + 只编码一次
        │
        ▼
ClientConnection：direct 同步写 / buffered 邮箱 + 容器异步写
```

一条 `send` 消息的主路径：

1. **握手与登记：** `/ws` 升级成功后写入 Tomcat 原生 5 秒阻塞发送超时，再登记连接；此时还没有加入房间。
2. **加入：** `join` 校验房间 ID/昵称，先检查新房间容量，成功后再移除旧成员关系；同房间重复加入只更新昵称。
3. **输入校验：** 先检查完整帧 UTF-8 字节数，再解析 JSON，校验 `clientMessageId`、正文和 `clientSentAt`。
4. **准入：** 先消耗连接令牌桶，再消耗房间令牌桶；任一层拒绝都返回稳定原因码，已消耗的其他层额度不退还。
5. **进入广播入口：** `local` 直接进入房间路径；`redis` 先尝试有界发布，发布结果通过 `send_result` 异步反馈。
6. **房间分发：** `direct` 在房间锁内取成员快照、分配序号并逐个投递；`fair` 先进入有界房间队列，按批次轮转，首次实际分发时确定成员快照。
7. **输出：** `direct` 逐连接同步写；`buffered` 把消息放入连接邮箱，由共享提交线程发起容器异步写，完成回调释放预算并激活下一条。
8. **计数对账：** 服务端记录 `expected`、`enqueued`、`submitted` 等，客户端记录逐观众实收；两者对账但不能互相替代。

**两层队列的分工：**

- **房间入口队列（`fair`）** 保存“一次待扇出的输入”，限制一个房间和所有房间合计能积压多少条/多少字节，解决的是热点输入挤压分发线程的问题。
- **连接邮箱（`buffered`）** 保存“某个观众待发的消息”，限制单个连接和全局输出预算，解决的是慢观众积压的问题。
- 两层都满时行为不同：房间入口可以拒绝新输入；连接邮箱可淘汰过期的普通弹幕，控制回复不能静默丢弃，无法保留时关闭该连接。

更完整的代码阅读顺序见 [消息路径](docs/MESSAGE_PATH.md)，协议字段与拒绝原因见 [协议说明](docs/PROTOCOL.md)，类关系见 [类关系](docs/CLASS_RELATIONSHIPS.md)。

## 快速开始

### 环境

- JDK 21 或更高。已实际运行验证：Windows 的 Oracle JDK 25.0.1、WSL2 Ubuntu 的 Temurin 21.0.12。
- Maven 3.9+。源码通过 `--release 21` 编译。
- 可选：k6 `v2.0.0-rc1` 用于冒烟/压测；Python 3 用于实验工具；PowerShell 7 用于归档脚本。

### 构建与启动

在仓库根目录执行。如果还没有代码，先 clone 并进入目录：

```powershell
# 如果还没有代码
git clone https://github.com/Choco-emmm/DanmakuLab.git
cd DanmakuLab

mvn -B -ntp verify
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar
```

打开 [http://127.0.0.1:8080/](http://127.0.0.1:8080/)，用两个页面都加入 `lobby`，即可双向发送；把其中一个页面切换到 `another` 验证房间隔离。Ctrl+C 停止服务。

终端出现类似下面的日志即表示启动成功（耗时和端口可能不同）：

```text
Tomcat started on port 8080 (http) with context path '/'
Started DanmakuLabApplication in 2.345 seconds (process running for 2.789)
```

- 健康检查：`GET /actuator/health`
- 指标入口：`GET /actuator/metrics`
- 改端口：启动参数增加 `--server.port=8081`；配置源文件为 [application.yml](src/main/resources/application.yml)。
- Windows 上重新打包前先停止已启动的 JAR，避免运行中的文件被占用；开发时也可以用 `mvn spring-boot:run`。

### 一键演示（Windows / PowerShell 7）

```powershell
pwsh -NoProfile -File scripts/run/quickstart.ps1
```

脚本会自动使用 `target/danmaku-lab-0.1.0-SNAPSHOT.jar`（不存在时先执行 `mvn -DskipTests package`），等待健康检查通过后打开浏览器，按 Ctrl+C 停止。常用参数：

- `-Mode fair`：同时开启 `buffered` 和 `fair`，演示两层队列；默认 `direct`。
- `-Port 8081`：换端口。
- `-Build`：强制重新打包。
- `-NoBrowser`：不自动打开浏览器。
- `-RunSeconds 5`：5 秒后自动停止，适合自动化冒烟。

完整脚本见 [quickstart.ps1](scripts/run/quickstart.ps1)。

### 最小协议示例

不想点页面时，可以在浏览器开发者工具的 Console 里粘贴下面这段代码，直接验证加入、发送和接收：

```js
const ws = new WebSocket('ws://127.0.0.1:8080/ws');
ws.onmessage = (event) => console.log(event.data);
ws.onopen = () => {
  ws.send(JSON.stringify({ type: 'join', roomId: 'lobby', nickname: 'console' }));
  ws.send(JSON.stringify({
    type: 'send',
    clientMessageId: 'demo-1',
    text: '你好',
    clientSentAt: Date.now(),
  }));
};
```

服务端会依次推送类似下面的消息（`roomInstanceId`、`runId`、`connectionId` 等是服务端生成的 UUID）：

**1. 加入确认**

```json
{"type":"joined","roomId":"lobby","roomInstanceId":"...","connectionId":"..."}
```

**2. 发送确认**

```json
{"type":"send_result","status":"accepted","clientMessageId":"demo-1","roomSeq":1}
```

**3. 房间广播**

```json
{"type":"danmaku","runId":"...","roomId":"lobby","roomInstanceId":"...","roomSeq":1,"clientMessageId":"demo-1","clientSentAt":1792674000000,"senderId":"...","nickname":"console","text":"你好"}
```

默认 `direct` 模式下通常先收到 `send_result` 再收到 `danmaku`；`fair` 模式下确认可能晚于广播到达。完整字段、换房和拒绝原因见 [协议说明](docs/PROTOCOL.md)。

### 计数冒烟

保持服务运行，在另一个终端执行：

```powershell
New-Item -ItemType Directory -Force load-results | Out-Null
k6 run scripts/load/smoke.js
```

10 个同房间观众，另加 1 个其他房间探针；全部加入后以约 1 条/秒发送 60 条，预期 600 次唯一接收，约 61 秒完成。脚本会检查每个观众的接收数、拒绝/错误、重复、异常断连和串房，失败时 k6 返回非零退出码。这是工具与业务计数校验，不是容量压测。

更短的检查：

```powershell
k6 run -e MESSAGES=3 scripts/load/smoke.js
```

更换服务地址使用 `-e WS_URL=ws://127.0.0.1:8081/ws`。需要保留本次结果时，加 `-e SUMMARY_PATH=load-results/demo-smoke.json`。完整归档入口为 `pwsh -File scripts/run/smoke.ps1`，详见 [冒烟与基准说明](docs/BENCHMARK.md)。

## 运行模式与关键配置

默认组合是 `local + direct + direct`，保留最初的同步直发基线；优化路径需要显式开启。

| 组合 | 启动参数 | 行为 |
| --- | --- | --- |
| 默认基线 | 无 | 房间锁内同步逐个写网络；用于对照 |
| 有界输出 | `--lab.outbound.mode=buffered` | 每连接邮箱 + 共享异步提交池；每连接最多一条在途 |
| 房间公平调度 | `--lab.outbound.mode=buffered --lab.dispatch.mode=fair` | 房间/全局入口预算、批次轮转；`fair` 必须搭配 `buffered` |
| Redis 双节点 | 在上一行基础上加 `--lab.cluster.mode=redis --lab.cluster.node-id=node-a` | 两个 JVM 通过 Redis Pub/Sub 传播广播事件；仍按节点限流 |

完整的 Redis 双节点启动、故障注入和消息身份语义见 [双节点说明](docs/CLUSTER.md)。

主要配置（默认值来自 `application.yml`，数值是实验上限，不代表已实测容量）：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `server.address` / `server.port` | `127.0.0.1` / `8080` | 默认只允许本机访问 |
| `lab.max-connections` / `lab.max-rooms` | `2000` / `1000` | 应用登记层连接上限 / 非空房间上限 |
| `lab.max-frame-bytes` / `lab.max-text-length` | `1024` / `200` | 完整输入 JSON 的 UTF-8 字节上限 / 正文 UTF-16 单元上限 |
| `lab.connection-messages-per-second` | `5` | 单连接令牌桶补充速率，也是桶容量 |
| `lab.room-messages-per-second` | `50` | 单房间令牌桶补充速率，也是桶容量 |
| `lab.outbound.mode` | `direct` | `buffered` 才启用连接邮箱 |
| `lab.outbound.max-messages` / `max-bytes` | `256` / `65536` | 每连接输出预算；包含排队和在途的一条 |
| `lab.outbound.global-bytes` | `134217728`（128 MiB） | 所有连接共享的输出字节预算 |
| `lab.outbound.workers` / `task-capacity` | `8` / `2000` | 共享提交线程数 / 有界任务队列容量 |
| `lab.dispatch.mode` | `direct` | `fair` 才启用房间入口队列 |
| `lab.dispatch.max-messages` / `max-bytes` | `64` / `65536` | 每房间入口预算 |
| `lab.dispatch.global-messages` / `global-bytes` | `4096` / `4194304` | 全部房间共享的入口预算 |
| `lab.cluster.mode` | `local` | `redis` 才连接 Redis；需要同时开启 `fair` + `buffered` |

可观测性重点：

- `lab.connections`、`lab.rooms`：当前连接与房间数。
- `lab.messages.input` / `accepted` / `rejected`：输入、入口接受与拒绝；拒绝带有限原因标签。
- `lab.deliveries.expected` / `enqueued` / `submitted` / `cancelled`：投递分母、入邮箱、写完成入口与取消。
- `lab.writes.failed`、`lab.outbound.*`、`lab.dispatch.*`、`lab.cluster.*`：失败、预算、队列、就绪房间和 Redis 桥接状态。
- `expected` 是分母，`submitted` 是发送调用/写完成的计数，**客户端实收只能由客户端证明**。

## 复现实验

所有正式实验都要求：先停止旧服务、用指定的新 JVM/空结果目录启动、记录 JAR 路径与哈希，并在结束后核对连接、房间、预算和自建进程是否清理。下表只列入口，完整的服务启动参数、前置门槛和结果校验以对应文档为准。

| 编号 | 目标 | 入口 | 归档结果 |
| --- | --- | --- | --- |
| T0 | 工具与业务计数冒烟 | `k6 run scripts/load/smoke.js` | 600/600 次唯一接收 |
| T1 | 100/500/1000 连接基线 | `pwsh -NoProfile -File scripts/run/baseline.ps1 -Connections 100 -HttpUrl http://127.0.0.1:8080` | [T1 报告](docs/results/2026-10-23/t1-report.md) |
| T2 | 单房间吞吐阶梯 | `pwsh -NoProfile -File scripts/run/throughput.ps1` | [T2 报告](docs/results/2026-10-26/t2-report.md) |
| T3 | 千连接混合慢读 15 分钟 | `pwsh -NoProfile -File scripts/run/longrun.ps1 -Scenario T3 -Seconds 900 -Connections 1000 -SlowConnections 10 -HttpUrl http://127.0.0.1:8084` | [T3/T5 三轮](docs/results/2026-10-28/longrun-repeat-02/notes.md) |
| T5 | 千连接稳态 30 分钟 | `pwsh -NoProfile -File scripts/run/longrun.ps1 -Scenario T5 -Seconds 1800 -Connections 1000 -HttpUrl http://127.0.0.1:8084` | [T3/T5 三轮](docs/results/2026-10-28/longrun-repeat-02/notes.md) |
| T4 | 普通/热点房间四阶段对照 | `pwsh -NoProfile -File scripts/run/hotspot-workers.ps1 -RunFormal` | [T4 三对](docs/results/2026-10-27/t4-workers-formal-01/notes.md) |
| T6 | 突发断连与退避重连 | `pwsh -NoProfile -File scripts/run/reconnect.ps1` | [T6 报告](docs/results/2026-10-26/t6-report.md) |
| Redis | 双节点广播与故障 | `pwsh -NoProfile -File scripts/run/cluster.ps1 -Connections 100` | [双节点报告](docs/results/2026-10-26/redis-report.md) |
| Linux | 同步/异步停读诊断 | `python3 scripts/run/linux-io.py --java-home <JDK> --jar <JAR> --output <新目录> --seconds 90 --rate 40 --viewers 10 --port 18081` | [Linux 诊断](docs/results/2026-10-30/linux-io-01/notes.md) |

**端口与 JAR 说明：**

- 表中端口是各实验的独立启动约定：T2 默认 8085，T6 默认 8086，Redis 固定 8087/8088，T3/T5 示例用 8084；请与对应文档的 `--server.port` 保持一致。
- T3/T5 与 T4 脚本默认读取 `target/danmaku-lab-release.jar`；T4 还会强制校验发布哈希 `BC565288…`。
- 如果刚用 `mvn verify` 构建了新包：T3/T5 可显式传 `-JarPath` 指向实际启动的 JAR（例如 `target/danmaku-lab-0.1.0-SNAPSHOT.jar`）做新的实验；T4 会拒绝非发布哈希，必须先准备已批准的 `target/danmaku-lab-release.jar`。
- 归档数字绑定各自的候选 JAR，不能用新构建的包直接复现后对比。

操作说明按场景拆分：

- [压测设计与结果记录规范](docs/BENCHMARK.md)
- [T1 连接规模基线](docs/BASELINE.md)
- [T2 单房间吞吐](docs/THROUGHPUT.md)
- [T3/T5 长测](docs/LONGRUN.md) 与 [三轮重复编排](docs/LONGRUN_REPEAT.md)
- [T4 热点房间](docs/HOTSPOT.md) 与 [1/5 热点工作进程](docs/HOTSPOT_WORKERS.md)
- [T4 短诊断状态机](docs/HOTSPOT_DIAGNOSTIC.md)
- [T6 重连](docs/RECONNECT.md)
- [慢消费者小规模复现](docs/SLOW_CONSUMER.md)
- [Linux / WSL2 复跑](docs/LINUX_IO.md)

## 项目结构

```text
src/main/java/io/github/chocoemmm/danmakulab/
├── protocol/        # /ws 入口：解析、校验、限流、按 type 路由
├── core/            # 连接与房间、广播、连接邮箱、fair 调度、令牌桶
├── cluster/         # 可选 Redis Pub/Sub 桥接、有界发布/接收队列
└── config/          # Lab / Outbound / Dispatch / Cluster 配置绑定
src/main/resources/static/   # 演示页：原生 HTML、CSS、JavaScript
src/test/java/               # JUnit 单元测试与真实 WebSocket 集成测试
scripts/load/                # k6 与 Python 负载/故障工具
scripts/run/                 # PowerShell/Python 采集、编排与归档入口（含 quickstart.ps1）
scripts/tests/               # 工具自身测试和离线复核脚本
docs/                        # 设计、协议、操作说明、逐轮实验记录
```

## 测试与验证

常规 Java 测试与打包：

```powershell
mvn -B -ntp test
mvn -B -ntp verify
```

真实 Redis 集成需要独立的 Maven profile 和可连接的 Redis；没有该 profile 时普通测试不要求 Redis：

```powershell
mvn -B -ntp -Predis-integration '-Dlab.artifact-name=danmaku-lab-redis' verify
```

工具侧测试（按修改范围选择执行）：

```powershell
python -m unittest discover -s scripts/tests -v
pwsh -NoProfile -File scripts/tests/metrics.test.ps1
pwsh -NoProfile -File scripts/tests/process-evidence.test.ps1
```

历史记录：2026-10-28 最终候选的 82 项常规 Java 测试与本地 Surefire XML 核对一致，失败/错误/跳过均为 0；2026-10-27 发布检查包含 82 项常规 Java、1 项真实 Redis 集成和 44 项 Python 测试。后续 T4/T3/T5 轮次又补充了 Python、Node 和 PowerShell 工具测试，具体数量与范围见各轮报告。测试数量会随工具演进变化，引用时应注明日期和制品。

## 文档导航

**入门与代码阅读**

- [制作计划与每日任务](docs/PLAN.md)
- [架构与消息语义](docs/ARCHITECTURE.md)
- [一条弹幕经过两层队列与完成回调](docs/MESSAGE_PATH.md)
- [当前协议与实现边界](docs/PROTOCOL.md)
- [类关系](docs/CLASS_RELATIONSHIPS.md)
- [第一次协作的代码阅读与练习](docs/LEARNING.md) / [学习问答](docs/LEARNING_QA.md) / [代码走查](docs/WALKTHROUGH.md)
- [五分钟演示、源码讲解与简历草稿](docs/demo-and-resume.md)

**机制说明**

- [有界输出邮箱与慢连接对照](docs/BOUNDED_OUTBOUND.md)
- [慢连接为什么连带正常观众，以及异步修复](docs/ASYNC_OUTBOUND.md)
- [房间入口队列与批次调度](docs/ROOM_DISPATCH.md)
- [Redis 双节点运行、消息身份与失败语义](docs/CLUSTER.md)

**实验与结果**

- [性能实验汇总与实测边界](docs/performance-report.md)
- [逐轮实验记录目录](docs/results/)
- [提交前改动的审查、修复和分组记录](docs/results/2026-10-26/prepublish-review/README.md)
- [发布检查与制品哈希](docs/results/2026-10-27/ship-verification/README.md)
- [归档版本变更记录](CHANGELOG.md)
- [提交与推送检查清单](docs/RELEASE_CHECKLIST.md)
- [跨阶段待办入口](TODOS.md)

## 已知边界与非目标

- **不适合直接当生产聊天系统。** 如果你需要账号权限、历史消息、可靠投递、全局严格顺序或透明扩容，应选择成熟的消息/聊天基础设施，而不是本项目。
- **没有账号、权限和普通弹幕历史。** 加入房间只依赖房间 ID 和昵称，不提供登录态或消息回溯。
- **不承诺可靠交付。** 断线期间消息不补发；`buffered` 的普通弹幕可能过期淘汰；慢连接可能被关闭。
- **不承诺永久去重。** `clientMessageId` 用于关联一次请求和统计，不是幂等键；Redis 的 `unknown` 结果也不自动重发，避免制造重复。
- **不承诺全局严格顺序。** 单房间内按 `roomSeq` 递增；Redis 模式保留来源节点的消息流，跨来源不比较单一全局序号。
- **不提供透明负载均衡。** Redis 双节点只让两个 JVM 各自管理自己的连接，连接分散不能直接表述为容量翻倍；限流、序号按节点计量。
- **1000 连接不是最大容量。** 没有 5000/10000 连接、公网、独立压测机或独占服务器的正式证据；Windows 长测与 Linux 11 连接诊断不能互相替代。
- **平台矩阵有限。** 主力长测在 Windows 11 完成，Linux 只做了 WSL2/JDK 21 的 11 连接诊断；macOS 和独立 Linux 服务器尚未验证。
- **`fair` 不是“必然更快”。** 它提供有界入口和批次轮转；T4 只证明在固定条件下完成完整投递，没有证明所有阶段延迟都优于 `direct`。
- **T3 的慢组会退出。** 慢探针在背压后被保护性关闭且不补建，因此结论限定在“初始 1% 慢读、正常组完整接收”，不能写成全程零丢失。
- **数字有制品边界。** 换 JAR、换配置、换机器后，旧结果不能直接复用；引用时必须同时说明模式、人数、输入速率、测量窗口和统计口径。

## 反馈与支持

这是个人学习与作品展示项目，暂不接受外部功能 PR。发现文档、脚本或数据问题时，欢迎在 [GitHub Issues](https://github.com/Choco-emmm/DanmakuLab/issues) 提出，并尽量附上：

- 复现步骤、期望结果和实际结果；
- 运行模式、JAR/提交、端口和操作系统；
- 相关结果目录或日志片段，而不只是摘要。

## 版本、证据与下一步

- 当前归档版本为 `0.3.0.0`（见 [VERSION](VERSION)）；Maven 工件仍为 `0.1.0-SNAPSHOT`，两者用途不同。发布候选 JAR 的 SHA-256 以 `BC565288…` 开头，完整哈希见 [性能报告](docs/performance-report.md) 和 [发布检查](docs/results/2026-10-27/ship-verification/README.md)。
- `v0.3.0.0` 已合入 `main`，并创建 annotated tag `v0.3.0.0`；原 `codex/hotspot-baseline` 分支已清理。
- 2026-10-28 已完成 T3/T5 各三轮、T4 三对正式重复；2026-10-30 完成 Linux/JDK 21 小规模诊断；2026-11-01 完成 README 审计、MIT 许可证、演示截图与一键演示脚本，并合入 main。工作区已提交推送，正式归档版本以 `VERSION`、`CHANGELOG.md` 和各结果目录中的制品哈希为准。
- 仍需完成：用户亲手演示与讲解、发布前检查；可选的后续验证包括千连接 Linux、Redis 30 分钟双节点稳态、异机/公网条件和完整 P0 交付清单核对。
- 本项目的目标是“把做过的实验讲清楚”，不是宣称已经覆盖所有生产场景。面试或简历中引用数据时，请同时带上 [性能报告](docs/performance-report.md) 中的条件与限制。

## License

本项目采用 [MIT License](LICENSE) 开源，允许商用、修改、分发和再许可；使用时请保留版权声明和许可证文本。

Copyright (c) 2026 Choco-emmm

代码、文档和实验记录均按 MIT 发布；引用实验数据时请注明项目来源。
