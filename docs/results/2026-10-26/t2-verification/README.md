# T2验证与源码快照

[正式报告](../t2-report.md)记录首轮结果，原始数据在[t2-formal-01](../t2-formal-01/notes.md)。Java业务代码、默认配置和JAR不变；本次新增的是学习文档、T2工具与计数验证。

## 验证入口

- `python -m unittest discover -s scripts/tests -p test_throughput.py -v`：13项测试，真实计数逻辑覆盖去重、错误发送者、房间/正文/序号、预热/测量分开、固定分母、缺失确认、跨进程时间戳与直方图合并。已执行通过。
- `python scripts/tests/throughput-summary.test.py docs/results/2026-10-26/t2-formal-01 --formal`：独立读取分片原始计数和直方图，不调用生产汇总函数，正式复核通过。
- [audit.py.txt](audit.py.txt)：再核对实际SLO数值、每位观众固定分母及server-after的12项Gauge，生成[additional-audit.json](../t2-formal-01/additional-audit.json)。
- [analyze-resources.py.txt](analyze-resources.py.txt)：核对55份源码与JAR哈希，汇总资源并从原始采样绘图；PNG已目视检查，SVG同时保留。

## 为什么这些测试有用

同一广播收两次只能计一次；少一位观众时分母不缩小；接受确认不能来自错误的观众；各进程看到的同一输入必须对应同一时间戳/序号。直方图测试用“一进程1000个快样本，另一进程1个慢样本”证明不能平均进程P99，另外验证120秒外溢出仍保留真实最大值和总和。错误、缺失和慢样本都不能为了通过测试而删除。

## 精确字节快照

[throughput.py.txt](throughput.py.txt)、[longrun.py.txt](longrun.py.txt)、[throughput.ps1.txt](throughput.ps1.txt)、[verify-summary.py.txt](verify-summary.py.txt)、[test-throughput.py.txt](test-throughput.py.txt)保存正式窗口对应代码，原文件扩展为.txt便于归档阅读。客户端只复用longrun的网络编解码/连接/直方图，未改变历史T3/T5脚本。

未来src/scripts变化后，源码SHA不匹配应保留为提醒，不要重写历史manifest。1000连接预检后对离线归档检查做过一处序号标准修正，正式轮已使用最终检查器；没有回填预检清单或把短轮称为正式结果。

本轮没有并行其他压测：进程检查为本轮父进程和四个Python子进程，用户8084连接为零。自建8085在12项Gauge归零后停止，用户服务保留。当前改动未提交推送。
