# T3/T5 客户端工具验证

当前长测固定Java制品，未在运行期间修改或重建服务端。新增 asyncio 原始TCP客户端和编排入口补足旧工具的连接/时长限制；完整代码快照、哈希、离线结果保存在本目录。

- Python标准库 unittest 共12项通过：原慢读工具5项，新工具7项。覆盖初始化预算、失败发送口径、帧分段/掩码/非法帧、延迟直方图、正常观众分母/重复、确认来源和非法序号。
- [60连接真实收发](../longrun-tool-smoke/run-status.json)：6000/6000 次测量投递，P99=4ms。
- 预检明确区分失败/成功：8线程下1000和20连接的10慢观众场景失败，32线程对应两轮通过。见 [诊断](../mixed-writer-diagnosis.md)。
- `scripts/tests/longrun-summary.test.py` 另核对原始证据，`--expect-failure` 只表示失败记录完整，不表示负载通过；`--formal` 额外要求900/1800秒、1000连接、20/s等正式条件。

慢观众只在预热后限速，若被服务端断开不自动补建。报告是初始1%慢观众、持续观察900秒的测试；不宣称900秒始终维持1%在线慢观众，也不宣称可承受任意比例慢连接。慢组潜在投递分母一直按初始人数保留，正常组分母从不缩减。

输入JSON固定256字节，网络掩码/帧/TCP头不在这个数中；慢组限速的是实际recv字节，约束含WebSocket封装。根目录samples中CPU是累计进程秒，资源只按采样报告，不当成绝对峰值。

资源汇总和曲线的派生代码也保存在本目录，哈希见 `analysis-source-sha256.json`。可执行 `python docs/results/2026-10-25/longrun-tool-verification/summarize-formal-resources.py.txt <结果目录>`；绘图入口为同目录 `plot-formal-resources.py.txt`，使用本机 Matplotlib。图中32线程标题仅针对本轮候选T3/T5，不能移用到8线程T4。CPU累计秒差除以墙钟秒差表示等效逻辑核数，不是整机CPU百分比；高水位属于进程生命周期，不把前一轮已达到的值相减作为本轮峰值。

四轮最终复核输出保存在 `formal-validation.txt`。`formal-archive-audit.json`显示四轮JAR相同、各轮源码清单与最终文件逐字节一致、JSON/JSONL和本地链接有效；审计代码快照为 `audit-formal-longrun.py.txt`。最后确认零连接后停止本轮启动的8082/8083/8084服务，记录在 `service-cleanup.json`，未停止其他端口的用户服务。
