# 第三天：有界输出邮箱与慢连接对照

2026-10-24：已接入 direct / buffered 两种发送模式。房间入口有界队列和房间公平调度仍未实现，不能把本阶段当作整个 P0 完成。

## 1. 先修正实际发送超时

原代码将 `org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT=5000L` 写进 Spring 的 `session.getAttributes()`。
本机 Spring 6.2.19 的业务属性 Map 独立于原生 Jakarta Session 的 `getUserProperties()`，不会自动把这个值转交给 Tomcat。
本机 Tomcat 10.1.55 的 `WsRemoteEndpointImplBase.getBlockingSendTimeout()` 读取原生用户属性，未配置或类型不为 Long 时使用 20,000 毫秒。
这解释了旧停读实验中的约 20 秒等待；锁竞争和客户端尾延迟也会叠加，不能把任何端到端延迟都等同于单次 socket 超时。

当前 Handler 获取 `StandardWebSocketSession.getNativeSession()`，把 Long 值写入正确的原生属性。`SendTimeoutTest` 在修改前确实失败、修改后通过。
本机版本依据 Maven 依赖对应的源码 JAR 和类字节码，未外推其他容器或版本。

## 2. 两种模式如何运行

默认 `lab.outbound.mode=direct` 保留同步逐个发送。另一个模式为 `buffered`：

```text
协议校验与输入限流
  → 房间锁内确定成员快照、分配序号、生成一次 JSON
  → 按顺序投递到每个连接自己的邮箱
  → 释放房间锁

固定发送线程池
  → 取出某连接邮箱的消息
  → 单连接串行写入网络
  → 返回后释放该条预算
```

buffered 的房间锁仍保护消息分配与入队顺序，但不持锁等待 socket 写入。`accepted` 仍表示通过输入准入，没有新增房间入口队列，也不表示所有输出都已入队或可靠交付。
同一连接的 joined、send_result、pong、错误及弹幕都经过同一个邮箱和实际写入口，控制回复不能绕开发送串行化。

## 3. 资源与过载规则

| 参数 | 默认值 | 计量口径 |
| --- | --- | --- |
| 每连接条数 | 256 | 队列内与正在写但尚未返回的一条合计 |
| 每连接字节 | 65,536 | 每份投递完整 JSON 的 UTF-8 字节数 |
| 全局字节 | 134,217,728 | 所有连接的上述预算之和；共享消息仍按每份投递保守计费 |
| 输出年龄 | 1000ms | 从进入邮箱到尚未取出写入；使用 System.nanoTime，周期检查约每 100ms |
| 发送线程 | 8 | 所有连接共享，任务队列最多 2000 个激活连接 |
| 单次发送批量 | 16 条 | 批次结束重新排队，不让一条连接无限独占执行器 |
| 网络关闭线程 | 2 | 独立于发送线程，关闭队列最多 max-connections 个一次性任务 |

- 连接邮箱达到条数或字节上限时，优先移除尚未发送的最旧普通弹幕；在途消息无法撤回。
- 控制回复不能作为普通弹幕淘汰。没有可淘汰弹幕、单条超出邮箱字节上限、全局预算不足或发送池拒绝激活时，注销并异步关闭该连接。
- 未发送普通弹幕过期后丢弃；未发送控制回复过期则关闭连接。这个年龄不是已进入传输层消息的有效期。
- 单次实际网络发送失败或超时后关闭连接，队列预算立即释放；在途条目的预算直到写入返回的 finally 才释放。
- 关闭、错误、到期检查可能同时到达，生命周期标志保证只安排一次关闭、预算不重复释放。
- 处于失败关闭中的连接继续占输出服务的连接名额，关闭任务完成后才归还，防止循环建立连接堆满关闭队列。正常网络断开则直接释放名额。
- 应用强制停机是例外：关闭池取消尚未执行的任务时，显式记录放弃事件并归还其名额，底层连接交给 WebSocket 容器退出清理；不能在停机线程同步补做可能阻塞的网络关闭。已经执行的发送/关闭仍在自身 finally 中归还预算/名额。
- 应用预算只核算业务载荷，不包含对象头、执行器、JVM、容器和内核缓冲。它不等于进程总内存的硬上限。

8 个发送线程意味着足够多的慢连接仍可能暂时占满发送池。当前只验证有界资源及初步对照，不承诺任意比例慢连接下完全隔离；正式 1000 连接、1% 慢连接和长时间验收仍需执行。

## 4. 实际指标与测试入口

- `lab.deliveries.enqueued`：普通弹幕输出入队次数，包含后续可能丢弃的消息。
- `lab.deliveries.submitted`：实际发送调用正常返回次数，两种模式口径一致，仍不能代替客户端接收数。
- `lab.outbound.bytes/messages`：当前总预算，包含在途；全部断开并写入收尾后应回到零。
- `lab.outbound.bytes.highwater`：进程启动以来预算高水位；不把复用进程的高水位当成本轮独立峰值。
- `lab.outbound.dropped{reason=overflow|expired}`：尚未发送的普通弹幕被主动淘汰。
- `lab.outbound.discarded{reason=connection_closed}`：连接作废时取消的排队条目，包含控制回复。
- `lab.outbound.closed{reason=...}`：邮箱过载、全局预算、控制超龄、执行器拒绝、写失败或停机等关闭原因。
- `lab.outbound.slots`：含正在失败关闭的连接名额，实际关闭收尾后归零。
- `lab.outbound.close.abandoned`：应用强制停机时放弃的未启动关闭任务数；名额已归还，但不表示该任务实际执行了网络关闭。
- `lab.outbound.tasks/writers.active`：发送队列任务数与正在执行的发送任务数。
- `lab.outbound.mode.buffered`：0 为同步基线，1 为有界邮箱，用于避免对照时连错服务。

```powershell
cd D:\coding\DanmakuLab
mvn -B -ntp verify
python -m unittest discover -s scripts/tests -v
pwsh -NoProfile -File scripts/tests/metrics.test.ps1
pwsh -NoProfile -File scripts/tests/process-evidence.test.ps1
```

Java 集成测试对 direct 和 buffered 分别启动随机端口，复用同房间收发、跨房间隔离、并发序号、错误、换房和连接清理场景。
邮箱单元测试通过闸门明确阻塞一个发送者，再验证另一个正常连接能推进、同一 Session 不并发写、UTF-8 预算与在途计费、丢弃、过期、拒绝、异常及重复注销。
最终提交前验证为 32 项 Java、5 项 Python、5 项 PowerShell 指标和 5 项真实子进程证据回归；另覆盖首次失败与停机交错、强制取消关闭任务的名额回收及在途预算，原始日志见 [提交前验证](results/2026-10-24/ship-verification/notes.md)。

## 5. 四组真实 TCP 对照

先在两个终端启动同一个新 JAR。Windows 上打包前先停止占用目标 JAR 的旧进程；运行过程中不重新打包覆盖它。

```powershell
# 终端 A：修正超时后的同步基线
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar --server.port=8081 --lab.outbound.mode=direct
```

```powershell
# 终端 B：只改变输出模式
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar --server.port=8082 --lab.outbound.mode=buffered
```

终端 C 执行，默认每组 90 秒输入加排空，总时长约七分钟：

```powershell
cd D:\coding\DanmakuLab
pwsh -NoProfile -File scripts/run/slow-compare.ps1
```

入口固定同一个 Python 脚本、10 个正常观众、先入房探针、每秒 40 条和 200 汉字正文，顺序执行四组，每秒左右采集一次指标及双端进程资源。
正常组和停读组是客户端读取方式不同；direct 和 buffered 是服务端输出方式不同。两种服务均使用修正后的有效 5 秒超时，不能把昨天 20 秒默认值的结果直接当成同参数优化对照。
每组目录拒绝覆盖，失败时保留日志与已经取得的采样。编排成功只说明运行和清理结束，性能是否通过还要检查 summary.json 的实际输入、拒绝、接收、延迟和原始指标。

采样记录为 CPU 累计秒和进程工作集，CPU 占比需用相邻样本的 CPU 秒差除以实际间隔，并明确是否按逻辑核数归一化。采样最大值不代表绝对峰值。

## 6. 代码阅读顺序

首轮实测与边界见 [2026-10-24 输出报告](results/2026-10-24/outbound-report.md)。

1. `OutboundProperties`：可配置的资源上限。
2. `ClientConnection.send`：direct 写网络，buffered 只入邮箱。
3. `OutboundMailbox.offer → drain → release`：预留预算、单写者发送、完成后归还。
4. `OutboundMailbox.expire/cancel`：过期与断开如何避免重复释放。
5. `OutboundService`：全局预算、固定线程池、关闭任务与名额回收。
6. `DirectBroadcaster`：同一份房间序号和 JSON，如何在两种输出模式下复用。
