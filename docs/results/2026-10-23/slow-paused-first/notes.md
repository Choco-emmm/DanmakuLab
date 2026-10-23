# 首轮慢连接复现与工具错误

本轮计划 90 秒、40 条/秒、10 个正常观众加 1 个停止读取的探针。
探针 SO_RCVBUF 实际查询为 1024 字节，收到 joined 后不再读取 TCP，仍尝试每 10 秒发送应用心跳。
约 5～20 秒的客户端控制台样本中，accepted 停在 74、正常接收总数停在 730；25 秒样本开始恢复。
threads-during.txt 抓到同步发送线程在 SocketWrapperBase.write → sendMessageBlockInternal → ClientConnection.send 等待，且持有 Room 锁；其他广播线程在等待同一 Room 锁。
这是实际发送等待与房间锁竞争证据，不是仅延迟网页显示。

本轮约 30 秒时，探针心跳写入遇到 ConnectionResetError，初版工具未处理此异常，提前退出 1，没有生成 summary.json。
不能把本轮称为完整 90 秒测试。保留线程栈和部分服务端快照，不覆盖；服务端快照采集时尚不存在的 lab.writes.failed 返回 404，因此该文件不是完整指标集合。
修订工具后，心跳写失败记录到 failures，停止该连接的后续心跳，仍保留全部正常观众在分母中继续测试；后续使用新的目录复测。
