# 最终候选的T3/T5独立重复

入口要求PowerShell 7.4及以上、Python、Java，固定使用已批准的发布JAR
`BC5652880AD8FA34B903B7F6350E292D090FA914C85456879243082A38F43F65`。
只创建本轮自己的8085服务，不关闭已有服务，不修改Java或application.yml。

```powershell
cd D:\coding\DanmakuLab
# 默认仅做20连接和1000连接的T3/T5短验收。
pwsh -NoProfile -File scripts/run/repeat-longrun.ps1
# 前置通过后自动执行三轮T3、三轮T5；实际测量合计135分钟，另加预热/收尾。
pwsh -NoProfile -File scripts/run/repeat-longrun.ps1 -RunFormal
```

可用OutputDirectory指定全新目录。已经存在的结果目录拒绝覆盖；正式轮失败后
停止剩余轮，不自动重试或从以前的成功结果拼接三轮。

## 固定条件

每轮新JVM：local、fair、buffered、8个输出提交线程，128MiB初始堆、256MiB最大堆。
所有轮次顺序运行、同一制品和工具源码；准备时保存源码ZIP快照和SHA-256，
每轮前后检查冻结清单。长批次申请防自动空闲休眠，结束释放，不修改电源计划。

T3每轮1000人，其中前10人是慢读观众，先全员正常读取预热60秒，然后测量900秒。
慢组每秒约1024字节，直接在socket.recv前控制读取额度并限制SO_RCVBUF；
心跳仍可发送，不是断网或延迟页面渲染。慢组断开后不补建，不能宣称整个15分钟
始终维持10个慢观众。正常990人始终留在分母，每人必须收到18000条测量弹幕，
共17820000次；预热另计1188000次正常投递。

T5每轮1000名正常观众，预热60秒、测量1800秒，每人测量期必须收到36000条，
共36000000次，预热另计1200000次。两种场景输入20条/s，完整JSON为256个UTF-8字节。

## 通过规则

正常组全员就绪、发送确认齐全，实际速率为目标±5%，最大发送滞后小于1000ms；
无正常组失败、重复、乱序或协议错误，抽样P95小于200ms、P99小于500ms。
接收计数来自客户端解析的真实弹幕事件，按每位观众去重并保留逐观众计数，
不能用服务器调用send次数代替。

T3允许慢组被保护性丢弃或断开，必须有服务端写失败或输出降级证明形成真实背压。
T5要求服务端投递expected/enqueued/submitted与所有正常观众实收一致，输出丢弃、
房间丢弃和写失败均为0。T3的慢组初始潜在需求、实际接收、读字节数和断开分别保存。

HTTP指标采样复用连接，有单请求和总截止。每轮结束在有限10秒窗口核对完整12项
Gauge：连接、房间、输出字节/消息/名额/任务/活跃提交线程/在途写，以及房间字节/
消息/房间数/就绪数；缺失或null不能当0。外层编排再核对一次，关闭自己持有的JVM。

longrun.ps1也保留单轮入口，但要求实际服务命令使用指定JAR绝对路径，以确认
测的是哪个制品。开发态spring-boot:run不能拿磁盘上的JAR哈希冒充身份；优先使用
上述一键入口，它自动使用绝对路径并记录命令、PID与哈希。

## 阅读与暂停

先看批次report.md和batch-status.json，再看每轮summary.json、counter-delta.json、
cleanup-gauges.json及evidence-index.json。原始progress.jsonl和samples.jsonl位于
索引指向的.lab-artifacts独立目录；结果摘要与原始目录应一起保存。

运行中可在结果根目录创建formal-hold.json，下一轮开始前会停止；当前轮仍正常
测量、排空和清理。用户主动睡眠、关机或结束进程会使当前轮无效，应保留记录。
本入口不自动提交、推送或合并。

离线复核：

```powershell
python -X utf8 scripts/tests/longrun-summary.test.py <某轮原始目录>/load --formal
```

旧记录不强行补新字段。新版evidenceSchemaVersion=2强制核对真正的JAR路径/哈希
和全部12项清理值。历史检查通过只说明历史证据有效，不计入最终候选的新三轮重复。
