# Ship Step 8：方案完成审查

日期：2026-10-27，Asia/Shanghai。审查方式：只读核对代码、文档、实验原始记录与归档哈希；本审查没有运行服务、修改业务文件、提交或推送。

基准：`origin/main`，加当前分支已有提交、全部未提交及未跟踪内容。当前已有提交包含有界邮箱发布，后续异步输出、公平调度、T2～T6与Redis均纳入本轮发布审查。`git log origin/main..HEAD`有4条历史提交；未把`git diff ...HEAD`看不到的未跟踪实现遗漏。

方案来源：用户明确的《Redis 双节点弹幕广播方案》、`docs/PLAN.md`当前进度、`docs/CLUSTER.md`、`docs/results/2026-10-26/prepublish-review/README.md`。本轮是当前实现及首轮证据的发布，不是整个10月29日最终交付清单的验收。

## 核对清单

| # | 状态 | 核对项与具体证据 |
| --- | --- | --- |
| 1 | DONE | 修改前单机回归：redis-implementation/baseline-tests.txt记录65项通过，先于Redis修改；与审查后79项分开记录。 |
| 2 | DONE | 源码、旧JAR和历史实验保留：baseline-archive.json绑定59个源码条目及63C3旧JAR；本次重新计算本地ZIP哈希，等于C649F04E…B39。旧制品位于.lab-artifacts，mvn clean不会删除。 |
| 3 | DONE | 新广播入口与默认本地实现：BroadcastIngress、LocalBroadcastIngress；DanmakuHandler最终交给ingress.submit。默认local配置。 |
| 4 | DONE | Redis强制fair+buffered：RedisBroadcastIngress构造校验；invalidModesFailAtConstruction；真实错误启动日志invalid-mode-startup.txt。 |
| 5 | DONE | 节点内连接/房间令牌桶与预算：协议入口保留原限制；CLUSTER/PROTOCOL明确两节点各50/s不等于集群总50/s。 |
| 6 | DONE | 发布FIFO→Redis→接收FIFO→本地调度→邮箱：RedisBroadcastIngress.publishLoop/receiveLoop、FairRoomDispatcher.acceptBroadcast、原异步输出路径。 |
| 7 | DONE | 发送节点只经订阅回流，避免双发：onlyEchoDeliversAndPublishAcknowledgementDefinesAccepted；真实双节点及RedisTransportIT均计一次。 |
| 8 | DONE | 接收端不再次发布；无本地房间忽略：receiveLoop只调acceptBroadcast；currentRoom为空返回false，不创建空房间。 |
| 9 | DONE | 已编码入口保留身份，不重新限流/分配序号/回发送者：acceptBroadcast只执行本地容量检查、入队及激活。FairRoomDispatcherTest相关远端用例。 |
| 10 | DONE | Redis网络操作位于成员/房间/邮箱锁外；发布、接收各唯一工作线程：publishOne短Room临界区后publish；BudgetQueue仅短监视器保护；网络回调非阻塞入队。 |
| 11 | DONE | 新增/修改处有邻近中文线程、预算、错误注释：网络层本轮补齐volatile与首次建连/恢复说明；根AGENTS要求在审查记录中逐项复核。 |
| 12 | DONE | 服务端messageId UUID、originNodeId及来源runId/roomInstanceId/roomSeq：encode生成并在接收端原样保留；客户端逐来源流检查顺序。 |
| 13 | DONE | 页面来源节点+序号与本地joined实例语义：app.js来源标签；CLUSTER/PROTOCOL说明joined属于本地生命周期，不误认全局递增。 |
| 14 | DONE | 本地协议/计数语义保留：LocalBroadcastIngress仅适配原路径；完整回归及Redis关闭时默认local真实k6收齐30/30。 |
| 15 | DONE | 版本化信封、最大4096 UTF-8字节：来源保守编码大小及接收长度检查；超大整数版本范围已修复并真实双节点验证。 |
| 16 | DONE | 两桥接队列各256条/1MiB且包含在途：ClusterProperties/YAML默认；BudgetQueue.take不释放，Slot.close幂等；条数与字节单测同时覆盖。 |
| 17 | DONE | 发布排队1秒、Redis操作1秒：maxAgeMillis与RedisURI/socket timeout；过期测试及真实CLIENT PAUSE 1800ms产生unknown。 |
| 18 | DONE | 无无界桥接执行器、CallerRuns或旧消息重发：固定线程、BudgetQueue；发布autoReconnect=false、requestQueueSize=1，发布异常废弃连接。 |
| 19 | DONE | 发布前确定拒绝区分满/过期/未就绪：bus_publish_full、bus_publish_expired、bus_unavailable等；明确前置失败与unknown不同。 |
| 20 | DONE | PUBLISH确认后accepted，订阅数仅指标：publishOne成功后result；订阅数只计publish.subscribers；观众接收由客户端独立计数。 |
| 21 | DONE | 已尝试但结果不确定为unknown，页面不自动重发：通用发布异常分支、app.js、uncertainPublishIsUnknownAndNeverRetried及真实暂停轮。 |
| 22 | DONE | 接收满/非法/本地拒绝分别计丢弃，不改accepted：receive/receiveLoop、固定原因标签；localQueueRejectionDoesNotRewriteAcknowledgement。 |
| 23 | DONE | 失败发布连接关闭，恢复仅新请求；订阅确认后ready且不补发：RedisPubSubTransport；redis-outage-final保存ready降0/升1、离线拒绝与恢复收发。 |
| 24 | DONE | 总线就绪、发布结果、接收/丢弃、桥接数量字节指标且无消息ID标签：RedisBroadcastIngress指标注册、固定status/reason标签、两节点快照。 |
| 25 | DONE | Boot管理Lettuce版本，未单独升级Spring：pom.xml保留Boot3.5.16，直接lettuce-core无自定义版本；默认local不受Redis健康检查影响。 |
| 26 | DONE | 专用Redis镜像固定digest、16379、两个JVM8087/8088及node-a/b：deploy/redis-compose.yml、命令/环境证据；未复用用户其他Redis。 |
| 27 | DONE | 新/旧Java关键语义回归：prepublish-java-final.txt为79通过0跳过；桥接容量、过期、来源、自回流/不重发、unknown、停机均有测试；另43项Python与两类PowerShell回归。 |
| 28 | DONE | 真实Redis集成明确单独执行：redis-integration Failsafe profile、RedisTransportIT；prepublish-redis-final.txt为1通过0跳过，连接失败不算通过。 |
| 29 | DONE | 100观众+两隔离探针、两节点20/s×60秒：redis-100-final逐观众120000/120000、1200输入，无重复/串房/来源倒序；独立统计核对通过。 |
| 30 | DONE | 1000观众各500人相同规则：redis-1000-final为1200000/1200000，抽样120000个，P99=40ms；Java/Redis/客户端资源时序均保存。 |
| 31 | DONE | 节点退出与切节点：redis-node-exit-final保留B固定观众3000/3000，50人重join B，必达15000/15000；退出节点最终计数明确为部分，不补写0。 |
| 32 | DONE | Redis中断/恢复与已尝试超时：redis-outage-final离线40条拒绝，前后12000/12000；redis-timeout-final unknown=1、不重发，前后12000/12000。 |
| 33 | CHANGED / PARTIAL | 桥接压满、接收满、过期与本地房间拒绝采用FakeTransport/受控调度测试，明确覆盖反馈与预算；没有真实网络将这些队列压满的端到端轮。目标中的拒绝/丢弃语义有证据，但不能宣称真实过载验收全部完成。 |
| 34 | DONE | Redis慢读观众：redis-slow-final真实低SO_RCVBUF+约1024B/s recv且发心跳，正常12000/12000；慢组关闭，59输出丢弃/1写失败如实报告。 |
| 35 | DONE | 独立证据目录、命令/环境、源码/JAR哈希、双节点指标与客户端真实接收：六个redis-*-final目录及原始manifest、raw-sha256；预发布复核13目录均通过。 |
| 36 | DONE | 正常轮两节点各16项连接/成员/在途/预算归零，退出轮仅可读取的B归零；network-cleanup.json另记录修订版两节点32项零值。清理记录只停自建节点和专用Redis，用户8084保留。 |
| 37 | DONE | 新操作说明、跨节点报告、架构协议任务状态与学习消息路径：CLUSTER、redis-report、ARCHITECTURE、PROTOCOL、PLAN；明确MQ仍需本地邮箱、accepted不等于收到、来源序号非全局顺序。 |
| 38 | DONE | 依方案实现阶段不自动提交推送：审查快照仍大量未提交；本轮用户新授权“收尾”后才由父agent执行发布，不能在前期报告中声称已上传。 |
| 39 | DONE | 单机公平调度/异步邮箱及T2～T6首轮证据纳入本次发布：代码与测试存在；prepublish-review/evidence-recheck.json独立核对T2/T3/T4两组/T5/T6两组。T5测量窗口36000000/36000000；无容量翻倍或公平全面更快的无证据结论。 |

合计：39项，38 DONE，1 CHANGED/PARTIAL，0 NOT DONE，0 UNVERIFIABLE。JSON的changed=1表示这项测试方式变化，并不抹去表中的端到端过载验证缺口。

## 发布判断与限制

**没有发现必须阻止“当前首轮实现与证据归档”发布的未实现核心路径。仅有PARTIAL项时，ship Step8允许带明确说明继续。** PR应逐字说明“桥接队列满/本地房间拒绝为受控离线测试，未做真实网络压满”。不得把该项写成所有真实过载已验收。

另一个必须保留的证据边界：六个正式网络轮属于D54AC0E…9798制品；审查后9B60AC1A…38D0修复正文配置与版本校验，其证据是完整79项Java/43项Python/1项真实Redis及真实双节点4/4边界验证，尚未重复100/1000和全部故障。prepublish-review已清楚区分，不用旧结果冒充修订版逐字节相同。正式发布后的重复实验可测最终发布HEAD。

本次重新计算了长期旧单机ZIP、审查后JAR、审查后源码ZIP三项SHA256，与索引一致。大量历史数据的逐条验证依据已有只读复核日志，不将本审查描述为重新运行全部压测。父agent的fresh verification gate仍需根据本轮后续代码变化判断是否重跑。

以下是已明确后续交付工作，不计为本次漏实现或标完成：关键独立对照至少三次、统一最终性能报告、5分钟演示/简历、可行时Linux/异机验证。实时历史持久化、Kafka/RabbitMQ、透明负载均衡、全局严格顺序、集群总限流、百万连接与容量翻倍均不在本轮承诺。

{"total_items":39,"done":38,"changed":1,"deferred":0,"unverifiable":0,"summary":"已核对本轮39项：38 DONE，1 CHANGED/PARTIAL，无NOT DONE或UNVERIFIABLE。核心单机异步输出/公平调度与Redis双节点路径、基线归档、79 Java/43 Python/1真实Redis、100/1000逐观众收齐、节点/Redis故障及清理均有证据。需保留限制：桥接压满/本地房间拒绝为受控离线测试，非真实网络压满；正式规模与故障数据绑定D54制品，审查后9B60仅完整回归与4/4边界实测，不冒充全部规模重测。本轮仅PARTIAL测试方式项，允许明确说明后发布。关键三次独立重复、最终性能报告和演示简历已留后续，不标为完成。"}
