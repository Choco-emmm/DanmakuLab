# 独立对抗审查（2026-10-27）

范围：当前工作区相对 origin/main 的实现改动，以及未跟踪的 Redis、房间调度、原始 TCP 压测源码。不读取技能或其他审查者报告；不修改业务代码，不操作任何服务。

## 结论

FIXABLE：未发现有充分证据、能够实际触发且应阻断本轮发布的问题。

INVESTIGATE：无。本轮未把缺少认证、历史补发、集群全局顺序或可靠交付视为问题，这些均属于已明确排除的目标；也未把仅凭设想的极窄线程窗口提升为缺陷。

## 核对依据

- 完整读取 RedisBroadcastIngress、RedisPubSubTransport、BudgetQueue、ClusterTransport、ClusterProperties：发布与接收各单线程；在途仍占预算；自节点只通过订阅路径投递；接收路径不再次发布；发布连接关闭自动重连，失败后关闭且不自动重发原命令；NotAttempted 与 unknown 分开；订阅确认后才 ready。
- 完整读取 FairRoomDispatcher、Room、ConnectionRegistry、DirectBroadcaster、DanmakuHandler：scheduled 同时覆盖运行/就绪状态；成员快照在调度锁外；接收保持来源身份；源房间退休后的排队任务取消；停机对正在运行的头部与等待项分别归还预算。未发现可证明的相反加锁顺序。
- 完整读取 OutboundMailbox、OutboundService、ClientConnection：每连接最多一条在途写；同步/异步完成交错由 invoking/completed 控制；重复回调与发起后抛异常不重复释放；断连后的在途预算与连接名额等回调结束；关闭任务与发送任务独立且有界。
- 完整读取 OutboundAsyncTest 与 RedisBroadcastIngressTest：现有用例覆盖同步重复回调、延迟成功/失败、预算保留、更多慢写连接仍不饿死正常连接、发布满/过期/编码失败/房间退休、接收满、unknown 不重试和停机清理。此审查未另跑 Maven，避免与主流程同时修改 target 测试结果；这段仅说明所读测试的断言，不声称新执行通过。
- 完整读取 cluster.py、cluster.ps1 和其复用的 longrun.py：正常观众分母固定；逐观众去重与收齐，实际速率与发送迟到单独校验；节点退出阶段明确只要求始终在线组、恢复阶段重新要求全体；已退出节点最终指标标记不可取得，未伪装成零。unknown 故障探针与吞吐窗口分开；慢读在 recv 前限制字节，不靠延迟显示模拟。

## 判断边界

Redis Pub/Sub 的 accepted 不是观众收到，断线窗口无补发，内部总线信任域不提供强敌对发布者隔离。实现与工具已将这些边界显式呈现；没有据此宣称可靠消息、全局严格顺序或连接数翻倍即容量翻倍。

Recommendation: proceed because independent source inspection found no reproducible release-blocking defect in the reviewed concurrency, budget, Redis failure, or receipt-denominator paths; retain existing explicit delivery and experiment limits.
