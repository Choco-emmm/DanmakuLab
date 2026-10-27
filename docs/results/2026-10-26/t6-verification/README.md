# T6工具快照和独立复核

保存本轮客户端、采集器、测试、共享WebSocket编解码及子进程证据模块的逐字节.txt快照，另有Matplotlib分析脚本。不改写T2/T3/T4/T5历史归档。

正式两轮source-sha256.json记录Java、配置、测试和脚本59个文件；分析时全部与工作区字节一致。JAR为已有异步制品，SHA256为`63C3F3BF6F415E0165FDA5EE710ACDFCA896D8E96981A0539314DD88C4994E28`。工作区含之前阶段未提交改动，不能把HEAD当该制品全部源码的已发布提交。

```powershell
python -X utf8 -m unittest discover -s scripts/tests -p test_reconnect.py -v
python -X utf8 scripts/tests/reconnect-summary.test.py docs/results/2026-10-26/t6-tool-100-01
python -X utf8 scripts/tests/reconnect-summary.test.py docs/results/2026-10-26/t6-formal-100-01 --formal
python -X utf8 scripts/tests/reconnect-summary.test.py docs/results/2026-10-26/t6-scale-500-01
python -X utf8 docs/results/2026-10-26/t6-verification/analyze-resources.py.txt docs/results/2026-10-26/t6-formal-100-01 docs/results/2026-10-26/t6-scale-500-01
```

12项单元测试通过，覆盖退避、抖动、关闭码、固定在线分母、跨代去重、错误确认、计划/意外关闭、输入拒绝、重试与总截止。真实归档复核不导入客户端report，另算原始接收、毫秒桶、代际ID、1013、等待、资源与最终12项Gauge。

三轮raw-sha256.json保存原始证据哈希，排除后加的notes、图表和派生摘要；后续可逐文件重算。100人短轮启动时复核器尚未创建，manifest少该文件，客户端与采集器字节相同。旧manifest不可因工具更新而改写。

图表已查看：一秒采样可能漏掉快速恢复、短暂队列与在途写，最终归零另查server-after.json。累计高水位不相减冒充本轮峰值。结论见 [T6报告](../t6-report.md)。
