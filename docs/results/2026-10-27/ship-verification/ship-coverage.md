# 发布测试覆盖审查（2026-10-27）

范围：`origin/main` 到当前工作区，包含 tracked diff 与 `git ls-files --others --exclude-standard` 找到的未跟踪运行源码、测试及工具。HEAD 为 `b9e25e9`。框架：pom 中 JUnit 5/Surefire、显式 Failsafe Redis IT，以及 scripts/tests 下 Python unittest；AGENTS 没有额外覆盖率阈值。

本次受 ship Step 7 的 30 路径上限约束，下表统计 **30 个明确列举的关键行为组**，不是完整分支清单。28 组有自动测试或可重复执行的集成场景，2 组留有缺口，人工路径映射四舍五入为 **28/30 = 93%**。没有运行 JaCoCo，**不能把 93% 写成代码行/分支覆盖率，也不能推断整个大型 diff 已穷尽覆盖**。

质量标记：★★★＝行为、边界或错误路径；★★＝正确路径；★＝仅冒烟；GAP＝该特定行为无直接可维护测试。`→E2E` 表示需跨实际组件验证。历史网络测试是已保存证据，不是本次重跑。

```text
ENTRY / LOCAL                                              CLIENT / NETWORK
01 [★★★] Handler 校验、未加入、UTF-8帧/字段拒绝             WebSocketIntegrationTest
02 [★★★] 节点连接/房间限流，拒绝不进入分发                 RoomAdmissionTest, TokenBucketTest
03 [★★★] Registry 换房失败保旧成员、空房回收、重复清理       ConnectionRegistryTest
04 [★★★] local direct/fair 路由与真实观众实际接收           Buffered/FairWebSocketIntegrationTest [→E2E]
FAIR ROOM FIFO
05 [★★★] 房间/全局条数与字节、UTF-8、调度槽拒绝            FairRoomDispatcherTest
06 [★★★] ready去重、跨房轮转、同房并发输入共序             FairRoomDispatcherTest
07 [★★★] 单次快照跨批稳定、房间退休/重建不串旧事件          FairRoomDispatcherTest
08 [★★★] 队列过期、分发异常、停机与在途预算                FairRoomDispatcherTest
09 [★★★] 远端原样事件，无本地序号/accepted/再次发布          FairRoomDispatcherTest
CONNECTION MAILBOX
10 [★★★] 单连接在途≤1，普通弹幕/控制回复共序               OutboundMailboxTest, OutboundAsyncTest
11 [★★★] in-flight也计预算、条数/UTF-8/全局上限             OutboundMailboxTest
12 [★★★] 溢出只淘汰旧弹幕，控制回复满/过期关闭             OutboundMailboxTest
13 [★★★] 线程池拒绝、延迟失败、重复/同步回调幂等            OutboundAsyncTest, OutboundMailboxTest
14 [★★★] 关闭与在途交错、名额延迟归还、强制停机            OutboundShutdownTest, OutboundAsyncTest
15 [★★★] 慢观众多于提交线程时正常连接仍能完成写             OutboundAsyncTest + T3真实慢读场景 [→E2E]
REDIS PUBLISH
16 [★★★] 未就绪/确定未尝试→rejected，ack后accepted           RedisBroadcastIngressTest
17 [★★★] 超时→unknown、同输入不自动重发                     RedisBroadcastIngressTest + redis-timeout场景 [→E2E]
18 [★★★] 条数/字节含在途、stop与close幂等、满立即拒绝        RedisBroadcastIngressTest
19 [★★★] 发布排队TTL过期不PUBLISH                           RedisBroadcastIngressTest
20 [★★★] 排队房间退休不PUBLISH、编码异常拒绝且线程继续       RedisBroadcastIngressTest（本次新增2项）
21 [★★★] 完整信封4KiB，中文UTF-8字节发布/接收都限           RedisBroadcastIngressTest（本次新增1项）
REDIS RECEIVE / BUS
22 [★★★] 只订阅回流本地、自回声一次、原来源身份保留          RedisTransportIT + RedisBroadcastIngressTest [→E2E]
23 [★★★] 接收满不阻塞回调、坏JSON/版本/正文边界不污染        RedisBroadcastIngressTest
24 [★★★] 本地队列满只记丢弃、不重写accepted、停机清预算      RedisBroadcastIngressTest
25 [★★★] 总线中断/恢复无历史补发、节点退出切换、慢观众       cluster.py 三故障场景 + 既有正式证据 [→E2E]
26 [GAP] 初次Redis不可用→稍后首次订阅成功；首次建连中停机     →E2E，现有IT仅先启动Redis再建连接
CLIENT TOOL ACCOUNTING
27 [★★★] TCP分片/掩码、去重、来源身份、固定观众分母           test_longrun/throughput/reconnect/cluster.py
28 [★★★] 聚合分位数/溢出、半窗口异常、奇数稳定组抽样         Python统计单元测试 + 独立summary检查脚本
29 [★★★] cluster.send_result必须来自实际发送观众             test_cluster.py（父发布任务本轮已补强）
WEB UI
30 [GAP] unknown提示且不自动重发、来源标签的持久E2E回归       →E2E；已有临时Playwright与截图，未入可维护套件
```

## 新增测试与执行

只修改 `src/test/java/io/github/chocoemmm/danmakulab/cluster/RedisBroadcastIngressTest.java`，新增：

1. `retiredRoomCancelsQueuedPublicationBeforeNetworkAttempt`：闩锁固定第一条在发布中，第二条等待；来源退休后第二条明确 rejected/room_closed，发布调用仍一次，预算归零。
2. `encodingFailureRejectsWithoutPublishingAndWorkerContinues`：第二次正式编码抛异常，尚未发布不能 unknown；下一条仍 accepted，发布线程及预算继续工作。
3. `multibyteEnvelopeLimitAppliesToBothPublishingAndReceiving`：字符串长度小于 4096、UTF-8 字节大于 4096，发布与接收均拒绝，无本地分发。

新鲜执行：`mvn -B -ntp '-Dtest=RedisBroadcastIngressTest' test`，**15 tests, 0 failures, 0 errors, 0 skipped，BUILD SUCCESS**（10:34:52 Asia/Shanghai）。起初测试直接调用跨包非 public 的 Room.retire 导致编译失败；已改为 spy 控制只读 retired 状态并通过，未扩大业务可见性。

JUnit 常规总数预计从79增至82；本审查只重新执行该15项，**最终全套新鲜结果由父发布任务执行**。显式 Redis IT 原有1项，Python原有43项，父任务补强确认路由后报告44项通过。本审查另执行test_cluster.py的7项均通过，没有把旧结果冒充本轮重跑。测试文件数保持：14个 Java Test/IT 类、10个 Python测试文件（另有实际网络检查脚本），本审查新增3个方法，不是3个文件。

## Testing 专家发现及边界

没有发现需要改业务代码才能修复的已确认运行回归。以下均为信息项：

- **已解决：确认路由检测缺口**：原 `scripts/load/cluster.py` 根据 clientMessageId/status 计 send_result，却不校验确认接收者是不是该输入的发送者。父任务已添加有界发送者数组，在await发送前登记，确认分支先核peer.index再改状态，并补负路径测试；本审查只读复核并执行7项cluster统计测试通过。该项不计剩余缺口。
- **初次建连重试的直接测试缺口**：`RedisPubSubTransport.start` 的首次不可用重试、启动过程中 close，不能由“已订阅后 Redis 中断恢复”替代。适合独立可控 Redis 集成生命周期测试；无需让普通单元套件依赖外部服务。
- **页面回归未持久化**：已保存实际页面 unknown/来源标签/双向消息的 Playwright 检查证据，但脚本位于 target 临时目录。需要长期维护时，把关键页面交互纳入可重复套件；当前证据不能宣称已有持久 E2E CI。

测试隔离：单元测试用闩锁/替身隔离 Redis；真实 IT 显式 profile、独立频道，不可用时失败而非跳过。现有1秒排队过期测试睡眠1100ms，不靠1ms启动竞争。闩锁均有限等待并在清理释放。新增退休测试仅控制 retired 边界，真实Registry退休由现有测试承担。

Coverage gate：**本30组人工评估93%，超过默认80%目标；2个信息缺口已明确列出。此门只针对所列组，不是JaCoCo行覆盖率保证。** 没有提交、推送、停止服务或改写历史证据。

## 可供后续 QA 执行的计划

- `/`：A/B 两个地址加入同房；A输入B收到一次、反向同样；标签显示各自来源。
- `/ws`：就绪后发送，确认 accepted 与客户端实际接收分别计数；异房探针不收到。
- 超时：Redis短暂停止执行，页面 unknown，不自动重发；恢复后新消息正常。
- 初始不可用：先起应用后起Redis，只有收到订阅确认才ready；首次建连中关闭无剩余线程。
- 过载：发布/接收/房间/邮箱预算，正常观众固定分母；慢读只拖累自身并记录关闭/丢弃。
- 清理：连接、成员、在途与所有队列计量回零；只清理自建资源。
