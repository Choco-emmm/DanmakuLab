# Redis双节点广播：运行、学习与语义

本功能把两个独立JVM连接到Redis Pub/Sub。每个节点仍管理自己的Session、成员关系、房间调度和连接邮箱。它展示跨节点传播、资源边界和故障处理，不代表生产级多地域系统或容量翻倍。

## 启动三个进程

先启动本实验独立Redis，再构建新制品；不覆盖用于单机实验的旧JAR。Maven的带点属性在PowerShell中需要引号：

```powershell
cd D:\coding\DanmakuLab
docker compose -p danmakulab-redis -f deploy/redis-compose.yml up -d
mvn -B -ntp '-Dlab.artifact-name=danmaku-lab-redis' package
# 显式执行真实Redis集成测试，连接失败会失败，默认单元测试不要求Redis。
mvn -B -ntp -Predis-integration '-Dlab.artifact-name=danmaku-lab-redis' verify
```

分别在两个终端启动节点（先停止正在占用同名新JAR的自建实验进程再重新打包）：

```powershell
java -Xms128m -Xmx256m -jar target/danmaku-lab-redis.jar --server.port=8087 --lab.cluster.mode=redis --lab.cluster.node-id=node-a --lab.outbound.mode=buffered --lab.dispatch.mode=fair
java -Xms128m -Xmx256m -jar target/danmaku-lab-redis.jar --server.port=8088 --lab.cluster.mode=redis --lab.cluster.node-id=node-b --lab.outbound.mode=buffered --lab.dispatch.mode=fair
```

节点名必须不同且不能使用默认local。两个节点使用相同namespace和Redis地址；默认频道`danmakulab-dev:broadcast:v1`。Redis容器固定7.4.10镜像digest，映射本机16379，未持久化普通弹幕；本机其他Redis不受影响。Redis模式强制fair+buffered，错误模式直接启动失败。本地模式默认local，不连接Redis，health也不依赖Redis。

打开两个页面分别访问8087、8088，加入相同房间，从任一页面发送；两边都应收到一次，序号旁显示来源节点。将一边切到其他房间验证隔离。这不是透明负载均衡；节点退出时浏览器页面需要手动访问存活地址，测试客户端另有地址切换逻辑。

## 一条消息怎样走

1. `DanmakuHandler.send`校验字段与本节点的连接/房间令牌桶，交给`BroadcastIngress`。
2. local实现保持原direct/fair路径；Redis实现只把不可变任务加入有界发布FIFO。
3. 唯一发布线程检查任务年龄，分配来源房间序号，在锁外编码并执行一次PUBLISH。
4. 所有订阅节点（包括来源节点）收到同一信封。网络回调只做长度检查和非阻塞入队，不遍历房间或发送网络。
5. 唯一接收线程校验信封，通过`FairRoomDispatcher.acceptBroadcast`占本地预算。没有本地观众就忽略，不新建空房间。
6. 调度器把原广播送进本地观众邮箱，异步写和finish回调照常控制在途预算。接收路径从不重新发布。

这解释了为什么MQ/Redis不能替代每连接邮箱：最后写Socket的速度仍取决于各个观众，且Session只存在于拥有它的节点。锁继续保护本地多张表与预算，没有把Session存进Redis。

## 消息身份、确认与顺序

Redis信封为`{"schemaVersion":1,"event":{...danmaku...}}`，最多4096 UTF-8字节。event增加全局随机`messageId`与配置的`originNodeId`，原runId、roomInstanceId、roomSeq均表示来源节点的流，订阅节点原样保留。joined的roomInstanceId仍是当前连接所在节点的本地房间生命周期，不能拿它要求远端广播使用相同UUID。

同一输入在不同节点观众处必须有同一messageId及来源字段；序号只在`originNodeId+runId+roomInstanceId`范围内比较。不同来源的#1可以同时存在，不承诺全局严格顺序，过期、拒绝或断连可能留下缺口。节点名唯一是部署约定，本版不引入分布式节点注册服务。

- `accepted`：Redis回复PUBLISH已执行。返回的订阅者数不是实际处理数，更不是观众接收数；本地分发或输出仍可能丢弃。
- `rejected`：明确未尝试发布，或队列满、等待过期、总线未就绪等确定失败。
- `unknown`：已尝试PUBLISH，但超时或断线导致结果无法确认。消息可能已广播，页面明确显示结果未知，不自动重发。

来源节点不直发本地、只订阅回流，防止本地双发。发布连接禁止自动重连重放旧命令；失败后关闭连接，下个新请求才重新建连。订阅连接可恢复订阅，收到SUBSCRIBE确认后ready才恢复，断线期间不重放消息。本版不做永久去重或exactly-once承诺。

限流和连接/房间上限按节点计量：两个节点各50/s，并不等于集群总50/s。跨节点消息到达接收端后不再次执行输入令牌桶，仍受该节点的房间队列、全局预算及连接输出限制。

## 预算与指标

发布与接收队列各256条、1MiB，包含正在处理的一条；出队不立即释放预算，成功、失败和停机均在统一close路径归还。发布等待上限1秒，Redis操作超时1秒。满时拒绝或丢弃，不创建无界任务、不回退调用线程处理。

Redis自身的订阅输出缓冲也设置8MiB硬上限、2MiB/10秒软上限；这是另外一层传输预算，不等于Java堆。发布/接收线程以及Lettuce小规模I/O线程为全节点共享，并非每观众一套。

新增指标包括`lab.cluster.ready`、`publish.messages/bytes`、`receive.messages/bytes`、`published`、`received`、`local.enqueued/ignored`、`dropped`、`publish.results`，以及独立的`lab.messages.unknown`。原因标签来自固定代码，消息ID不作为指标标签。旧expected/submitted仍只描述本节点观众，集群实际接收必须由测试端逐观众验证。

## 验收命令与证据

以下入口要求两个实验节点已经运行、订阅就绪且无其他观众。输出目录不覆盖已有记录：

```powershell
pwsh -NoProfile -File scripts/run/cluster.ps1 -Connections 100
pwsh -NoProfile -File scripts/run/cluster.ps1 -Connections 1000
pwsh -NoProfile -File scripts/run/cluster.ps1 -Connections 100 -Scenario redis-outage
# Redis在线但暂停执行命令，验证真实unknown，不把已尝试的超时误报为确定拒绝。
pwsh -NoProfile -File scripts/run/cluster.ps1 -Connections 100 -Scenario redis-timeout
pwsh -NoProfile -File scripts/run/cluster.ps1 -Connections 20 -Seconds 30 -SampleEvery 1 -Scenario slow
# 此项会停止匹配实验JAR和8087启动命令的节点A，需要之后重新启动A。
pwsh -NoProfile -File scripts/run/cluster.ps1 -Connections 100 -Scenario node-exit
python -X utf8 scripts/tests/cluster-summary.test.py docs/results/日期/本轮目录
```

正常轮另有两个隔离探针：100位正常观众对应102条物理连接，1000位对应1002条；slow再增加一条SO_RCVBUF缩小、recv每秒约1024字节的慢读连接。心跳继续发送，不是只暂停网页渲染。node-exit的必达分母区分始终在线组与恢复全体，不用新连接身份删除历史漏收。

每轮保存两个节点命令、源码/JAR哈希、环境、客户端逐输入/逐观众原始计数及毫秒桶、服务器前后与资源时序。HTTP复用会话，资源约2秒采样，快照非原子且不是绝对峰值。退出节点无法读取最终计数，要明确标为部分计数，不能补写零；存活节点的16项连接/两层预算/桥接预算应归零。

Redis中断时控制连接仍存在，但不能发布；恢复后新消息正常收发，不补发离线输入。node-exit由客户端抖动后选择B，验证固定在线B观众持续收到以及恢复全员完整率。本机回环只能证明已测范围，不能外推百万连接或带宽线性增长。

首轮实现与验收见 [双节点报告](results/2026-10-26/redis-report.md)。单机完整源码/JAR的长期本地位置、哈希和回归日志见 [归档索引](results/2026-10-26/redis-implementation/baseline-archive.json)，本地二进制归档不受mvn clean影响，也不纳入Git。

提交前审查又修复了两处边界：订阅端不再用默认200字覆盖来源节点的可配置正文上限，仍受完整信封4KiB限制；版本号必须能精确放入long再与1比较，防止超大整数截断。来源节点与接收节点可以使用不同的输入正文上限，接收不重新执行输入准入。新增边界回归及修订制品见 [提交前审查](results/2026-10-26/prepublish-review/README.md)，首轮长短测的原始哈希保持不变。

2026-10-27，归档版本`0.3.0.0`已提交并推送至`codex/hotspot-baseline`，尚未合入main。最终候选SHA-256为`bc5652880ad8fa34b903b7f6350e292d090fa914c85456879243082a38f43f65`，通过82项普通Java、1项显式真实Redis集成、44项Python测试，真实双节点边界收到4/4次，10观众冒烟收到600/600次。两节点各16项状态归零，本轮自建节点与Redis已停止；详见 [发布检查](results/2026-10-27/ship-verification/README.md)及 [制品索引](results/2026-10-27/ship-verification/candidate-artifact.json)。该候选没有重新完成首轮全部规模与故障实验。
