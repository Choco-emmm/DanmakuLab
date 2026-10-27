# 房间入口队列与批次调度

2026-10-25 新增，可通过配置开启。默认仍走原调度路径；实现有界与轮转机制不等于已经证明吞吐或延迟收益。

## 两层队列与消息路径

```text
WebSocket 接收线程
  → JSON 校验、连接令牌桶、房间令牌桶
  → 房间入口：检查条数/字节/全局预算，编码一次，分配序号并入队
  → 释放入口锁，给发送者投递 accepted 控制回复

一个共享 lab-room-dispatch 平台线程
  → 取一个就绪房间的队首弹幕，首次分发时复制成员快照
  → 本轮最多尝试 64 份观众投递、最多完成 4 条弹幕
  → 还有任务则把房间放回就绪队尾

每连接有界输出邮箱 → 共享提交池 → 容器异步网络写入 → 完成回调释放预算并继续提交
```

房间输入队列保存“待广播的弹幕”；连接输出邮箱保存“待写给这个观众的消息”。一个房间可能有很多观众，必须同时限制单轮观众投递数，不能只限制弹幕条数。

2026-10-26的buffered输出已改为异步提交：默认8个提交线程不等待网络完成，每连接仍最多一条在途，完成回调后才归还预算并继续下一条。房间调度与连接输出是两层独立机制；具体并发边界见 [异步输出](ASYNC_OUTBOUND.md)。下文2026-10-25成绩属于修改前制品，不能直接当作新版实测。

例如热点 H 有 500 人，普通房间 N 有 50 人。H 尝试投递给其中 64 人后回队尾；如果 N 已就绪，就能得到下一轮机会。H 再回来时接着第 65 人。H 的第一条遍历完才能开始第二条，保持每位观众看到的房间序号递增。

入口：[FairRoomDispatcher](../src/main/java/io/github/chocoemmm/danmakulab/core/FairRoomDispatcher.java)、[配置对象](../src/main/java/io/github/chocoemmm/danmakulab/config/DispatchProperties.java)、[Handler](../src/main/java/io/github/chocoemmm/danmakulab/protocol/DanmakuHandler.java)。

## 模式与启动

| 房间调度 `lab.dispatch.mode` | 连接输出 `lab.outbound.mode` | 行为 |
| --- | --- | --- |
| direct | direct | 原来的逐连接同步网络广播 |
| direct | buffered | 原来的房间锁内按序投递连接邮箱 |
| fair | buffered | 房间入口排队、共享线程轮转分发、连接邮箱写网络 |
| fair | direct | 启动失败，避免让慢网络写阻塞所有房间的调度线程 |

停止占用旧 JAR 的服务后执行 `mvn -B -ntp verify`。两个终端分别启动同一 JAR：

```powershell
# 终端 A：原房间调度，输出仍启用邮箱。
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar --server.port=8082 --lab.outbound.mode=buffered --lab.dispatch.mode=direct

# 终端 B：新房间调度。
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar --server.port=8083 --lab.outbound.mode=buffered --lab.dispatch.mode=fair
```

另一个终端依次执行，不要同时施加两轮负载争抢同机资源：

```powershell
pwsh -NoProfile -File scripts/run/hotspot.ps1 -SplitProcesses -PhaseSeconds 10 -DispatchMode direct -HttpUrl http://127.0.0.1:8082
pwsh -NoProfile -File scripts/run/hotspot.ps1 -SplitProcesses -PhaseSeconds 10 -DispatchMode fair -HttpUrl http://127.0.0.1:8083
```

入口核对两个模式 Gauge，避免测错模式；fair 还必须检查房间预算与就绪状态归零。2026-10-25已完成一对每阶段三分钟的正式对照，两种调度各收齐11544500次投递，均未复现正常房间数百毫秒尾延迟，不能宣称fair性能优势。重复试验仍待完成；候选长测与默认线程配置失败见 [正式报告](results/2026-10-25/formal-longrun-report.md)。

2026-10-26异步输出版本的 [正式T4主对照](results/2026-10-26/async-t4-report.md)也已完成：两组默认8提交线程、四阶段各180秒，各收齐11544500次；普通P99原调度约17～32.01ms、fair约12～28ms。热点100/s档fair较慢、200/s档较快，工具发送滞后仍有影响，不能宣称普遍性能收益。首次fair采集失败保留，修正指标HTTP连接复用后以相同采集器重跑两组，主对照采集、计数与异步在途/两层预算清理通过；关键三次重复仍待完成。

## 容量、确认与顺序

| 约束 | 默认值 | 计量口径 |
| --- | --- | --- |
| 每房间输入 | 64 条、65536 字节 | 排队加正在分发的第一条，先到即拒绝 |
| 全局输入 | 4096 条、4194304 字节 | 所有房间共享，按完整广播 UTF-8 JSON 计字节 |
| 调度房间名额 | `lab.max-rooms`，默认 1000 | 包括待取消旧实例，就绪队列每实例最多一个节点 |
| 单轮工作 | 4 条完成或 64 份观众投递 | 先到即让出，大广播跨轮次保留游标 |
| 输入年龄 | 1000ms | 用 `nanoTime`，首次分发前检查；已开始的广播不半途按年龄丢弃 |

字节预算是逻辑载荷，不是 JVM 总内存。对象、队列节点、字符串、成员快照、序列化瞬时对象另有开销；条数和房间名额同时限制保留对象数量。每个正在分发的房间最多一份快照，其大小受连接上限限制。

fair 的 `accepted` 表示输入成功进入房间队列并分配序号。容量拒绝不占序号、不计 accepted，先前消耗的令牌不退。确认在放锁后走控制邮箱，广播可能先于确认到达。并发输入以服务端实际入队顺序为准；前一条未遍历完，不开始后一条。

已接受输入可能在首次分发前过期，留下序号缺口；输出层也可能丢弃。缺口不是乱序，没有历史重放或逐观众可靠交付。成员快照取自首次实际分发，后续批次复用；中途加入的观众不补这条已开始的广播。

最后一个成员离开时 Room 标记退休，调度器下一轮取消其剩余输入；同名新房间使用新对象、新实例 ID，不能接管旧队列。停止准入后取消等待条目；正在执行的第一条仍占预算，到轮次返回才释放。调度线程最多等待 7 秒退出，输出层仍负责自己的网络关闭。

## 拒绝、指标与锁

新增 `room_queue_full`（房间条数/字节）、`room_global_budget`（全局条数/字节）、`room_scheduler_full`（调度名额）、`room_closed`、`server_stopping`。都通过 `send_result/status=rejected` 返回原 clientMessageId，不是 Socket 发送异常。

- `lab.dispatch.messages/bytes/bytes.highwater`：保留输入条数、当前广播字节和字节高水位，含正在分发的条目。
- `lab.dispatch.rooms/ready`：保留房间状态数与就绪数；运行中的房间不在 ready 内，仍占 rooms。
- `lab.dispatch.mode.fair`：1 为 fair，0 为 direct。
- `lab.dispatch.wait`：入队到首次取得成员快照的计时，包含实际等待与快照获取时间。
- `lab.dispatch.completed`：已遍历完快照的输入条数，不能解释为观众全部收到。
- `lab.dispatch.discarded{reason=expired|room_closed|shutdown|dispatch_failure}`：接受后取消的输入条数。
- `lab.deliveries.cancelled{reason=...}`：已计入 expected、取消时还没尝试的投递数。首次快照前取消没有 expected，但客户端仍会发现已接受输入缺失，不能因此减少它的分母。

输入预算锁不跨成员快照、控制回复、观众邮箱投递或网络写入。Registry 退休标记不回调调度器，避免锁循环。异常分发记录日志/指标并释放资源，不能让共享线程悄悄永久退出。

## 验证与限制

单元测试覆盖条数/UTF-8 字节/全局预算/调度名额、并发顺序、轮转、跨批次快照、退休重建、过期缺口、异常恢复和停机；协议测试覆盖五种拒绝；真实 Tomcat 测试验证跨轮次广播确实被客户端收到。

这是有限工作量轮转，不是严格 CPU 配额或固定毫秒延迟承诺。序列化仍在输入线程短临界区执行，成员登记、发送池和主机资源仍共享。没有可复现的服务端瓶颈证据，就不能宣称 fair 获得了性能提升。
