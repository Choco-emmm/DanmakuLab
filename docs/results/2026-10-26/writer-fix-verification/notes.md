# 异步发送修复验证材料

- `regression-red.txt`：相同回归在修复前失败，1线程被慢同步写占住，正常连接没有得到原生异步发送调用。
- `regression-green.txt`：修复后同一核心场景通过。
- `maven-verify-64.txt`、`maven-test-65.txt`与`test-summary-65.json`：先全量verify，再补一个延迟成功回调FIFO测试，全量65项通过。新测试只改变测试源码，不改变运行中的Java制品。
- `runtime-source-sha256.json`与六份运行源码快照：与当时制品绑定。测量后仅补正Mailbox两条注释，旧快照与各轮清单保留原样。
- `compiled-class-match.json`：注释补正后重新编译，21份应用class逐字节匹配测试用JAR；没有重打包或把新制品替换进旧实验。
- `archive-audit.json`：核对三轮新JAR相同、运行源码（明确记录两行注释例外）、JSON/JSONL与文档链接。列出测试和采集脚本在各轮之后的变化，不改历史清单。
- `async-wait-sample.json`：在途10、提交线程活跃0的采样原响应，端点读取不是原子快照，仅作补充证据。
- 资源汇总和绘图、归档审计代码以`.py.txt`保存，可用Python执行；源文件哈希见`proof-source-sha256.json`。绘图标题8线程仅用于本轮新制品，不挪到旧32线程或同步版本。

独立复核命令（从仓库根目录执行）：

```powershell
python scripts/tests/longrun-summary.test.py docs/results/2026-10-26/writer-fix-before-20 --expect-failure
python scripts/tests/longrun-summary.test.py docs/results/2026-10-26/writer-fix-after-20
python scripts/tests/longrun-summary.test.py docs/results/2026-10-26/writer-fix-formal-t3-8writers-01 --formal
python scripts/tests/longrun-summary.test.py docs/results/2026-10-26/writer-fix-normal-1000-60s
```

`--expect-failure`只检查失败记录完整，不表示负载通过。旧JAR源于上一轮，仅在本地target保留二进制，不将其散布到公开仓库。结果均为Windows同机回环；不推导最大容量或任意比例慢读的保证。
