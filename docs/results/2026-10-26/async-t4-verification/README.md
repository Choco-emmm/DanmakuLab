# 异步版T4验证索引

正式主对照：[原调度02](../async-t4-direct-02/notes.md)、[fair02](../async-t4-fair-02/notes.md)。两轮使用相同51份源码清单、相同异步JAR和采集器，各收到11544500次投递，采集与清理通过。完整结论见 [主报告](../async-t4-report.md)。

## 已执行检查

- `scripts/tests/hotspot-summary.test.py`：真实逐房间/阶段需求、接收、拒绝、重复/乱序/隔离、进程退出、准备/开始对齐与服务端计数。
- [analyze-evidence.py.txt](analyze-evidence.py.txt)：额外检查正式参数、实际输入和发送滞后、JAR/SHA清单、模式Gauge、异步在途写归零、采样预算边界，生成每轮verification.json和resource-summary.json。
- [audit-pair.py.txt](audit-pair.py.txt)：两轮主对照配置除dispatchMode外完全一致，51份源码清单一致；四个正式窗口JAR一致，新旧轮仅hotspot.ps1采集器不同。结果见 [pair-audit.json](pair-audit.json)。
- [classify-failed-collection.py.txt](classify-failed-collection.py.txt)：首次fair01仍必须被原验收器拒绝，单独验证其业务完整而采集缺一帧，不篡改失败状态。
- [plot-resources.py.txt](plot-resources.py.txt)：Matplotlib从原始采样和各房间分位数绘图，未平均P99；PNG已目视检查，SVG同时保留。

从仓库根目录执行：

```powershell
python docs/results/2026-10-26/async-t4-verification/analyze-evidence.py.txt docs/results/2026-10-26/async-t4-direct-02 docs/results/2026-10-26/async-t4-fair-02
python docs/results/2026-10-26/async-t4-verification/audit-pair.py.txt
```

分析入口核对运行时源码哈希；未来修改src/scripts后出现不匹配是正常保护，不要为了通过而回填历史manifest。旧原调度01是在采集器修正前验证的，匹配当时源码；它的采集器原字节已另存。

## 采集器问题的证据

[原采集器](hotspot-before-connection-reuse.ps1.txt)、[精确变更](collector-session-change.diff)、[连接复用探测](probe-http-session.ps1.txt)、[首次工具输出转录](http-session-probe.json)均保留。探测比较相同60次请求的新建本地端口，原写法60、复用后1；它证明短连接积累模式，不是确定性复现瞬时WSAENOBUFS。脚本快照如需运行，先复制为.ps1到被忽略的target目录，并在无压测负载时执行。

60连接工具短轮见 [归档](../async-t4-collector-smoke/notes.md)，不是正式千连接成绩。首次fair采样失败见 [独立失败分类](../async-t4-fair-01/failure-classification.json)。所有原始状态、JSON/JSONL与历史SHA保持不变，派生结果另写文件。

两组正式负载结束后又从保存的探测脚本复核了一次（复制到target运行，读取空闲8084）：60次请求仍为原写法60条新连接、复用写法1条。最终94份JSON/JSONL解析通过，15份相关文档链接检查通过，未发现编码替代字符，`git diff --check`通过。主对照图和fair资源图已目视检查，PNG/SVG均保留。

本次只修改压测采集器和文档，没有修改Java、重建JAR、提交或推送。自建8082/8083服务均在连接/预算归零后停止，用户8084服务保留。
