# 提交前整理与审查

日期：2026-10-26（Asia/Shanghai）。范围：以当前已提交的`b9e25e9`为基准，审查全部未提交实现、工具、文档及相关实验记录。此阶段只准备可审阅内容，没有暂存、提交、推送、改版本号或创建PR。

## 这轮解决了什么

| 审查轴 | 发现与处置 |
| --- | --- |
| Standards：项目规范 | Redis网络层缺少共享字段线程归属、volatile原因、首次连接与订阅恢复的邻近中文注释；已补齐并复核。依据根AGENTS.md的并发与生命周期注释要求。 |
| Standards：设计判断 | FairRoomDispatcher两个入口重复预算与入队提交逻辑；属于可选重构建议，尚无行为错误证据，本轮保留，避免仅为消除重复而改写已测调度路径。 |
| Spec：正文长度P2 | 原订阅端固定text≤200，来源配置允许300时，201字可能获accepted后全部被丢弃。改为由来源入口校验正文上限，接收端保留完整信封4KiB及本地队列约束，不重做输入准入。 |
| Spec：信封版本P3 | `18446744073709551617`转long会截断成1；增加范围检查，非法版本计丢弃，不进入房间调度。这是内部总线的校验边界，不称公网漏洞。 |

两个轴分别复核：Standards原硬规则项已解决，保留1项可选重构；Spec两项已修复，无未解决的已确认行为问题。另将cluster.ps1默认归档目录改为运行当天日期，避免跨天仍写入10月26日。

## 新鲜验证

| 检查 | 结果及证据 |
| --- | --- |
| 修改前完整Java回归 | 77通过、0失败、0跳过；prepublish-java-tests.txt。 |
| 两项边界回归先复现 | 原代码2项均失败，分别是未分发201字、错误分发超大版本；prepublish-boundary-red.txt。 |
| 修复后完整Java回归 | 79通过、0失败、0跳过；prepublish-java-final.txt。 |
| Python工具回归 | 43通过；prepublish-python-final.txt。 |
| PowerShell工具回归 | 指标读取5项、子进程证据5场景通过；prepublish-metrics-tests.txt、prepublish-process-tests.txt。 |
| 显式真实Redis集成 | 1通过、0跳过，实际使用专用16379；prepublish-redis-final.txt。 |
| 打包 | 独立制品target/danmaku-lab-prepublish.jar成功；prepublish-package.txt。打包跳过重复执行测试，前一步已完成完整测试。 |
| 真实双节点边界 | A输入上限300，B默认200；两条双向输入、4/4次接收、无重复；非法大版本每节点丢弃1次。见prepublish-network-boundary.json。 |
| 已有实验离线复核 | T2、T3、T4两组、T5、T6两组及Redis六组，共13目录通过；见evidence-recheck.json。这里是原始证据复核，并非重新跑长测。 |
| 语法与差异 | scripts/run/*.ps1解析、app.js与hotspot.js语法及git diff --check通过。 |
| 待发布文件检查 | 凭证特征扫描无命中；无JAR/ZIP/私钥文件候选。扫描只是固定特征检查，不代表完整安全审计。 |

真实边界脚本为[scripts/tests/cluster-network-boundary.py](../../../../scripts/tests/cluster-network-boundary.py)，复用原真实TCP客户端。测试节点独占`danmakulab-prepublish`频道，命令及关闭前全部32项零值见network-cleanup.json；节点日志另存本目录。两个自建节点与专用Redis均已停止，8084用户服务PID80284保持运行。

## 制品与历史证据

审查后JAR SHA256：`9B60AC1AAB023B57AF6E8B800137608CAA931C6E437F372B671A6B87DA5D38D0`。本地长期副本和本轮源码ZIP见[reviewed-artifact.json](reviewed-artifact.json)，位于Git忽略的`.lab-artifacts/`。该ZIP只绑定本轮源码/测试/脚本，不以旧HEAD冒充当前工作树。

旧单机63C3、首轮Redis网络测试D54、页面修订候选DC均保留，原始source-sha256和raw-sha256不改写。这次Java实现已经变化，不能继续宣称当前源码与首轮网络实验逐字节相同；首轮数据仍仅属于其原JAR。新版只新增边界真实网络验证，尚未重复全部规模与故障轮。

[verify-archive.py.txt](verify-archive.py.txt)按归档source.zip核对首轮132条Java源码哈希、102个原始证据哈希，再列出当前源码差异；不再用可变工作树代替历史快照。复核输出见archive-verification.json。原redis-implementation/audit.py.txt记录的是修订前时点，其“当前源码相同”检查不能直接用于本轮修订后的工作树。

## 建议提交分组

1. 运行实现及同组测试：房间公平调度、异步邮箱、Redis入口/传输、配置/页面/依赖与部署定义。文件清单见inventory.json；实际提交时应按依赖再拆分，保证每个提交均可编译。
2. 压测与证据：T2～T6及双节点工具、统计测试、正式/失败原始记录及本次检查记录。失败记录保留，不能只选成功轮。
3. 操作与学习文档：消息路径、单机/双节点说明、架构协议与当前任务状态。
4. 发布元数据：CHANGELOG已补“未发布”草稿；VERSION仍为0.2.0.0。正式版本和提交操作留到发布阶段。

未跟踪文件本轮整理前约31.6MiB，最大单文件约1.66MiB，主要为JSON与资源时序；二进制归档不进Git。精确清单与大小见inventory.json。没有压缩、删除或移动历史实验数据来掩盖失败。

尚未完成的项目收尾包括关键对照的独立重复、统一最终性能报告、演示及简历材料；本记录不把它们标为完成，也不宣称容量翻倍、公平调度全面更快或Redis可靠交付。
