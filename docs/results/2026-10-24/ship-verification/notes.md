# v0.2.0.0 提交前验证

本轮验证发生在两轮停读实验之后。审查复现了首次失败关闭与应用停机的竞态：连接已标记关闭但尚未提交任务时，停机提前关闭执行器，导致关闭任务被拒绝且名额未归还。
修复后，生命周期锁覆盖首次关闭决定、内存注销与任务提交；实际网络关闭仍在独立线程池执行。停机再次进入该连接的 fail 会等待首次提交完成。

## 验证证据

- 新增确定性交错测试 `shutdownWaitsForAnAlreadyStartedFailureToSubmitItsCloseTask`：[修改前失败](shutdown-race-red.txt)，[修改后通过](shutdown-race-green.txt)。测试使用闸门和有界等待，最终确认物理关闭恰一次、关闭名额归零。
- [首次候选 Maven verify](maven-verify.txt)为 31 项；继续补齐强制停机路径后，[最终完整 Maven verify](maven-verify-final.txt)为 32 项通过，Failures/Errors/Skipped 均为 0，JAR 打包成功。
- 强制停机取消未启动关闭任务也需归还名额，新增回归先[失败](forced-shutdown-red.txt)、再[通过](forced-shutdown-green.txt)；在途写仍自行释放预算，未执行的物理关闭明确交给容器退出处理。
- Python 工具回归 5 项、PowerShell 指标回归 5 项、真实子进程证据回归 5 项通过。后者验证正常输出、1 秒超时、采样回调异常、非零退出和启动失败均保存日志与状态；既有实验结果、JSON/JSONL、本地链接和常见凭据特征检查通过。
- [最终制品缓冲模式冒烟](smoke-summary-final.json)：11 个 joined、3 条 accepted、30/30 次唯一接收，错误、重复、串房均为 0；[首次候选冒烟](smoke-summary.json)另行保留。
- [最终 JAR 哈希](jar-final-sha256.json)与[首次候选制品](jar-sha256.json)分别保存。前两轮停读对照使用的旧 JAR 哈希和原始结果保持不变；本次没有把停机修复后的制品冒烟冒充完整性能重测。
- 修改后的采集入口还完成了每组 1 秒的四组工具短轮：[direct-normal](runner-short/direct-normal/summary.json)、[direct-paused](runner-short/direct-paused/summary.json)、[buffered-normal](runner-short/buffered-normal/summary.json)、[buffered-paused](runner-short/buffered-paused/summary.json)，均为 40 条接受、正常观众 400/400。该短轮只检查编排、回调与清理，不用于复现背压或比较性能。

## 审查与范围

测试/API、安全/对抗、性能/可维护性审查均定位到上述同一竞态；修复后独立安全复核确认未引入 Registry、服务监视器与生命周期锁的反向依赖。
独立 CLI [对抗审查原文](adversarial-review.txt)另指出强制停机取消与超时日志归档两个问题，本次均已修复并补回归。额外结构化 CLI 审查因本机执行工具初始化失败而不可用，不把它记为审查通过；保留主审、专业审查和成功完成的独立对抗审查结论。
本次范围为输出邮箱、预算、超时修正、过载与清理。房间入口队列、公平调度、热点隔离与正式长时间验收属于下一阶段，见 [待办](../../../../TODOS.md)。

原始两轮性能证据见 [当天报告](../outbound-report.md)和[用户复测](../slow-compare-205930/notes.md)。后续热点房间实验将使用新的工作分支与独立结果目录。
