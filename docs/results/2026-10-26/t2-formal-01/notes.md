# T2正式轮阅读入口

1000连接、四个接收进程、5/20/50/100条输入每秒，各预热15秒、测180秒。房间额度显式200/s，fair+buffered，默认8提交线程和256MiB最大堆，Java与JAR不变。

测量期31500000/31500000次全收，含预热34125000次；拒绝、漏收、重复、乱序、读失败、写失败、丢弃和采集错误为零，四档参考SLO和12项最终Gauge归零通过。分档P99=9/9/9/51ms，最高档最大发送滞后311.41ms，限制和资源解释见 [主报告](../t2-report.md)。

先看 [run-status.json](run-status.json)和[client/summary.json](client/summary.json)，再看[counter-delta.json](counter-delta.json)、[verification.json](verification.json)与[additional-audit.json](additional-audit.json)。[资源图](resources.png)和[资源汇总](resource-summary.json)来自samples.jsonl。

正式参数复核命令：`python scripts/tests/throughput-summary.test.py docs/results/2026-10-26/t2-formal-01 --formal`。源码/JAR清单按实际运行保留，不能回填为未来修改后的代码。
