# T6：突发断连与退避重连

T6检查一批观众断开后，服务器能否释放旧连接名额，并接纳新的连接；另一批始终在线的观众必须继续收齐消息。它不承诺断线期间补发历史弹幕。

## 1. 本轮怎样运行

默认100位观众加入同一房间，前50位始终在线，后50位连续三轮关闭TCP并重连。输入20条/秒，由始终在线组轮转发送，每条完整应用JSON为256字节。

| 窗口 | 时长 | 输入 | 必须收齐的观众 | 必须收到的投递次数 |
| --- | --- | --- | --- | --- |
| 初始baseline | 10秒 | 200条 | 全部100人 | 20000 |
| 每轮churn | 10秒 | 200条 | 固定在线50人 | 10000 |
| 每轮recovered | 10秒 | 200条 | 重连就绪后全部100人 | 20000 |

三轮加初始窗口共1400条输入，必达110000次。断连组在churn期间恢复早晚不同，额外收到的消息单独统计；不能拿这些额外接收抵消固定在线组的缺失。最初100人对应的潜在140000次需求也保留，不把离线观众从历史数据删除。

每次重连都新建真实TCP/WebSocket连接，再发join并检查全新的connectionId。固定在线组维持房间实例；重连组允许房间序号有缺口，但不允许重复或倒序。窗口之间有限排空，晚到消息按输入ID归回原窗口。

## 2. 退避与满额探针

第0、1、2次尝试前的等待上限分别是1、2、4秒，继续失败则8、16、30秒，最高30秒。实际等待是`随机数[0,1) × 本次上限`，所以各客户端分散重连。每个逻辑观众与轮次有独立、可复现的随机源。单次握手最多3秒，整轮恢复默认15秒、最多6次尝试，达到任一边界便失败退出。

初始100人在线时，额外3个探针各重试3次。独立服务显式把应用连接上限设为100，它们应全部收到真正的WebSocket关闭码1013；普通TCP EOF、超时或其他错误不能冒充准入拒绝。这9次预期拒绝与弹幕限流拒绝是不同计数。

这些探针真实覆盖1/2/4秒上限的重试等待。断连组如首次就重连成功，只能说明首次恢复成功；不宣称它们在网络上走过全部6级退避。更高上限另有单元测试。

本工具用Python模拟与网页相同的等待规则，未驱动浏览器的app.js。它关闭客户端TCP socket，不发WebSocket关闭帧；这会让服务器收到传输层关闭，并非拔网线后长期没有FIN的黑洞网络。不能据此证明浏览器交互或所有真实断网场景。

## 3. 启动和运行

先用空闲独立端口启动已有制品，保留用户正在阅读/演示的服务：

```powershell
cd D:\coding\DanmakuLab
java -Xms128m -Xmx256m -jar target/danmaku-lab-0.1.0-SNAPSHOT.jar --server.port=8086 --lab.outbound.mode=buffered --lab.dispatch.mode=fair --lab.max-connections=100
```

另一个PowerShell 7.4以上终端：

```powershell
cd D:\coding\DanmakuLab
# 先短轮验证工具；仍为100连接，一轮断连，三个窗口各2秒。
pwsh -NoProfile -File scripts/run/reconnect.ps1 -Cycles 1 -SteadySeconds 2 -ChurnSeconds 2 -DrainSeconds 1

# 默认三轮，输入窗口70秒，加建连、探针及排空约90秒。
pwsh -NoProfile -File scripts/run/reconnect.ps1
```

想提高到500人，先停止上述独立实验服务，再用同一个JAR启动、只把`--lab.max-connections=500`改为500；客户端使用`-Connections 500`，默认其中250人断连。入口核对进程命令的连接上限与模式Gauge，不会自行修改Java、yml、额度或用户服务。结果目录已存在则拒绝覆盖。

## 4. 读取结果

结果保存在`docs/results/日期/t6-时间/`：

- `client/summary.json`：逐窗口必达与实际接收、每位观众计数、延迟、每轮恢复耗时、新旧连接ID、每次随机等待和结果。
- `client/observations.json`：逐输入接受状态、原始接收数、时间戳、序号、逐观众计数和非零毫秒桶，供独立复核。
- `samples.jsonl`：约每秒一行，包含Java/Python累计CPU秒、工作集、线程与应用/JVM指标。整轮复用HTTP会话，采集失败不会抹掉。
- `server-before.json`、`server-after.json`、`counter-delta.json`：服务端计数增量、满额拒绝以及结束后12项Gauge是否归零。
- `config.json`、`environment.json`、`server-command.txt`、源码/JAR哈希及子进程输出状态：复现本轮环境与过程。

计划关闭TCP时，服务端可能已有旧连接的在途写，因此写失败或丢弃可以出现，不能宣称“全程零错误”。它们单独保留；通过依据是固定在线组和恢复就绪后的全员真实收齐、没有意外读失败/重复/乱序、恢复在截止内，以及资源正确回收。

成员变化时，全服投递快照、容器写完成和客户端读到的数量不必完全相等。独立检查合理边界`必达接收 ≤ 全体实际接收 ≤ submitted ≤ expected ≤ 已接受输入×初始人数`；不能用服务端submitted替代客户端接收。

延迟仅对本窗口必须收齐的观众抽样；完整率检查全部必达观众。参考线为P95<200ms、P99<500ms，实际输入需在目标±5%内且最大发送计划滞后<1000ms。每秒快照非原子，采样最大值不是绝对峰值；资源上限仍由实现和边界测试保障。

## 5. 复核命令与代码入口

```powershell
python -X utf8 -m unittest discover -s scripts/tests -p test_reconnect.py -v
python -X utf8 scripts/tests/reconnect-summary.test.py docs/results/日期/本轮目录
# 只对默认100人、三轮、10秒窗口的完整参数增加此开关。
python -X utf8 scripts/tests/reconnect-summary.test.py docs/results/日期/本轮目录 --formal
```

[客户端](../scripts/load/reconnect.py)负责断连、重试与收到后的计数；[采集器](../scripts/run/reconnect.ps1)负责服务器/进程证据；[单元测试](../scripts/tests/test_reconnect.py)验证退避和统计边界；[独立复核器](../scripts/tests/reconnect-summary.test.py)只读原始归档重新核对，不导入客户端的汇总方法。

阅读Java时，可对照 [消息路径](MESSAGE_PATH.md)中的ConnectionRegistry登记/清理与OutboundMailbox在途写回收。T6新增的是实验工具，业务消息路径无需为压测改动。
