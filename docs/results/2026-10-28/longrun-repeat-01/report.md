# T3/T5独立重复

前置通过：False；正式通过：0/6。

正常观众固定保留在分母；慢组丢弃/关闭与正常组接收分别报告。T3必须另有服务端背压证据。

| 轮次 | 场景 | 正式 | 通过 | 正常实收/应收 | P95/P99 ms | 最大发送滞后ms |
|---|---|---|---|---:|---|---:|
| smoke-T3 | T3 | False | True | 2000/2000 | 2/5 | 15.89 |
| smoke-T5 | T5 | False | True | 4000/4000 | 2/3 | 16.17 |
| scale-T3 | T3 | False | True | 396000/396000 | 123/194 | 910.98 |
| scale-T5 | T5 | False | True | 400000/400000 | 26/35 | 35.44 |

失败/停止原因：制品或源码变化，停止后续测量

未执行：formal-1-T3, formal-2-T3, formal-3-T3, formal-4-T5, formal-5-T5, formal-6-T5

每轮原始目录与文件SHA-256见evidence-index.json。正式失败后停止，未执行项不补跑。
本批使用local/fair/buffered、8个提交线程和固定发布JAR；不改变Java业务、不自动提交推送。
