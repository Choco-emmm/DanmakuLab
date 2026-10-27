# 房间调度验证记录

- `maven-test.txt`：`mvn -B -ntp test`，58 项通过；逐测试类计数见 `test-summary.json`。
- `maven-package.txt`：上述测试通过后 `mvn -B -ntp package -DskipTests`，仅打包，不算新一轮测试。
- `jar-sha256.json`：两轮千连接对照使用的同一制品哈希。
- [60 连接工具验证](../fair-smoke/run-status.json)、[原调度千连接](../dispatch-direct-1000/run-status.json)、[fair 千连接](../dispatch-fair-1000/run-status.json)均通过真实接收、计数与资源回收。
- 定量结果和适用限制见 [报告](../room-dispatch-report.md)。

性能 manifest 保存的是运行当时源码哈希。打包/实测后只将 `OutboundMailbox.drain` 的一行旧注释改为说明“本类负责连接轮转，房间轮转另由 FairRoomDispatcher 负责”，未改可执行语句；旧行可从归档源提交恢复，未重写性能 manifest。更早的单进程和拆分版压测入口分别保存原始字节快照。
