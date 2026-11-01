# Linux / WSL 网络 I/O 诊断

这是一组小规模学习实验：固定已有 JAR，观察停止读取的客户端如何影响 TCP 发送队列、
同步发送线程和连接邮箱。它不替代千连接正式长测，也不作为 Windows 与 Linux 性能排名。

## 环境与启动

在 WSL Ubuntu 中运行，服务与 Python 客户端都位于同一 Linux 实例内，通过 IPv4 回环连接。
需要 Linux JDK（含 `jcmd`）、Python 3、`ss` 与 `top`。Windows 的 java.exe 不能替代 Linux JVM。
使用显式 JDK 路径；`pom.xml` 的 release 21 只约束编译，实际 Java 21 兼容由运行结果证明。

```bash
cd /mnt/d/coding/DanmakuLab
python3 -m unittest discover -s scripts/tests -p 'test_linux_io.py' -v
python3 -m unittest discover -s scripts/tests -p 'test_slow_consumer.py' -v
python3 scripts/run/linux-io.py \
  --java-home /home/renpe/.local/share/danmakulab/temurin21-61312483 \
  --jar .lab-artifacts/ship-20261027/danmaku-lab-release.jar \
  --output load-results/linux-io-new-run \
  --seconds 90 --rate 40 --viewers 10 --port 18081
```

输出目录必须尚不存在。入口只启动并清理自己创建的 JVM 和负载进程；端口被占用时直接拒绝。
上面的 JDK 路径是本机从已有官方 Maven 镜像提取的 Temurin 21，其他机器替换为实际 Linux
JDK 目录；本机默认 `java` 与该显式路径可以不同，以各轮 `jvm-identity.json` 为准。
每组使用全新 JVM，128/256 MiB 初始/最大堆、local 总线、direct 房间调度与 8 个共享提交线程。
依次运行 direct-normal、direct-paused、buffered-normal、buffered-paused。每组 10 正常观众加
1 个最先加入的探针，40 条输入/s，200 汉字正文；只有探针是否继续 recv、输出模式发生变化。
没有排除预热窗口，发送后使用原负载工具的 7 秒排空。不得与旧工具或不同消息大小的 P99 直接混比。

可用 `--cases buffered-normal --seconds 3 --rate 10` 做工具冒烟；这不算正式四组完成。

## 证据怎样对应

- `environment.json`：Linux/WSL、JDK、JAR SHA-256、堆、TCP 缓冲配置、命令和脚本哈希。
- `tools/`：本轮冻结的诊断与负载脚本；实际负载执行冻结副本。
- 各组 `jvm-identity.json`、`commands.json`：真实 Linux JVM 的版本、启动参数和 PID。
- `client/summary.json`：逐观众接收、计划/实际输入、拒绝、失败、延迟和 socket 端点。
- `samples.jsonl`：约每秒的应用指标、两个进程 CPU 累计秒/RSS/线程数与 `ss -tinmp`。
- `threads-*.json`：约每 20 秒的 JVM 线程栈与 `top -H` 快照，含实际采样时刻。
- `before.json`、`after.json`、`status.json`：采集错误、12 项清理 Gauge 与进程退出证据。

`summary.clientEndpoints.probe.local[1]` 是探针本地端口，可在 ss 的服务端 socket 行匹配对端，
避免把 Actuator HTTP 连接或其他观众当成慢探针。ss 同时保存两个方向：服务端的 Send-Q
表示已经交给内核、尚未被对端确认的字节；客户端 Recv-Q 表示已经到达但应用尚未取走的字节。
TCP 接收窗口和实际缓冲大小需结合 ss 的后续信息读取，不能把配置值当作当前实际积压。

同步路径要找 `ClientConnection.writeNow`、Tomcat 发送等待、房间锁的持有/等待关系。
异步路径要结合 `lab.outbound.writes.inflight`、邮箱 bytes/messages、提交线程活跃数和丢弃/关闭原因。
在途写仍存在但提交线程已经返回，是区分“网络仍在等待”与“应用线程被占住”的重要线索。

## 判读标准

先确认实际输入完成、最大计划滞后、拒绝/失败、正常观众完整分母；退出码 0 不代表负载有效。
正常观众分母保留全部计划输入乘以全部正常观众，断开的正常观众不能被删去。
探针停读后的零接收是工具设置，只有 TCP 积压、线程等待或应用降级等证据才能支持背压结论。
未观察到应用背压时如实记录，可另建新目录延长窗口，不能改写首轮或只挑成功记录。

客户端延迟从发送前到解析广播，包含发送调用阻塞与客户端读线程调度；最大计划发送滞后是另一项指标。
本工具的延迟记录覆盖所有实际收到的正常投递，不是旧千连接工具的抽样 P99。服务端写完成不能代替客户端收到。
CPU 累计秒差除以墙钟秒得到等效逻辑核数；RSS 包含堆外内存。top 首帧不作为精确的一秒 CPU 利用率。
应用指标逐项读取、线程栈为离散快照，采样峰值不能当绝对峰值，采到零不能证明从未积压。

WSL2 与 Windows 共享物理硬件；同机回环没有公网延迟，也没有独立压测机隔离。
Linux/JDK21 与历史 Windows/JDK25 同时存在环境差异，不能把差值写成代码优化收益。
