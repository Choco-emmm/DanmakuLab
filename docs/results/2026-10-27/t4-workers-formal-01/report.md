# T4 热点工作进程实验

此报告不把工具运行结束、投递收齐、正式三对完成和旧问题根因定位混为一项。

- 工具回归通过：True；前置门槛通过：True。
- 正式通过：6/6；RunFormal：True。
- 固定 JAR SHA-256：`BC5652880AD8FA34B903B7F6350E292D090FA914C85456879243082A38F43F65`。
- 单实例、local、buffered、8个输出提交线程；同机回环，服务端和全部客户端争用同一台机器。
- 采样延迟为全局观众编号每10人取1人，热点合并毫秒直方图，普通房间P99各自保留。
- CPU口径为占用核心数，客户端内存为同次采样全部k6工作集之和；峰值是采样峰值。

| 轮次 | 热点进程 | 通过/证据完整 | 热点实收/应收 | 热点最大发送滞后ms | 热点最大定时器漂移ms | 热点20/100/200 P99 ms | 全客户端输入期峰值MiB |
|---|---:|---|---:|---:|---:|---|---:|
| [smoke-1](smoke-1/audit.json) | 1 | True/True | 20450/20450 | 14 | 16 | 4/3/3 | 49.4 |
| [smoke-5](smoke-5/audit.json) | 5 | True/True | 20450/20450 | 6 | 15 | 3/1/1 | 230.6 |
| [injected-5](injected-5/audit.json) | 5 | False/True | 20450/20450 | 1252 | 1500 | 1356/1/1 | 232.5 |
| [short-1-w1](short-1-w1/audit.json) | 1 | True/True | 624500/624500 | 544 | 548 | 20/519/20 | 230.3 |
| [short-2-w5](short-2-w5/audit.json) | 5 | True/True | 624500/624500 | 11 | 15 | 8/8/7 | 380.9 |
| [short-3-w5](short-3-w5/audit.json) | 5 | True/True | 624500/624500 | 11 | 15 | 7/7/7 | 381.5 |
| [short-4-w1](short-4-w1/audit.json) | 1 | True/True | 624500/624500 | 392 | 401 | 18/427/24 | 230.2 |
| [formal-1-pair1-direct](formal-1-pair1-direct/audit.json) | 5 | True/True | 10824500/10824500 | 15 | 22 | 7/7/7 | 430.1 |
| [formal-2-pair1-fair](formal-2-pair1-fair/audit.json) | 5 | True/True | 10824500/10824500 | 15 | 16 | 7/7/7 | 429.4 |
| [formal-3-pair2-fair](formal-3-pair2-fair/audit.json) | 5 | True/True | 10824500/10824500 | 13 | 16 | 7/6/6 | 425.2 |
| [formal-4-pair2-direct](formal-4-pair2-direct/audit.json) | 5 | True/True | 10824500/10824500 | 15 | 28 | 6/6/6 | 432.1 |
| [formal-5-pair3-direct](formal-5-pair3-direct/audit.json) | 5 | True/True | 10824500/10824500 | 15 | 20 | 6/6/6 | 423.0 |
| [formal-6-pair3-fair](formal-6-pair3-fair/audit.json) | 5 | True/True | 10824500/10824500 | 14 | 16 | 6/6/6 | 424.7 |

## 门槛与未执行项

- 前置门槛通过。
- 未执行：

## 阅读与复现

各轮 `audit.json` 保存独立合并对账、热点分位数、普通房间逐阶段P99和错误；`resources.json` 将准备、输入、排空、关闭分开。
`evidence-index.json` 指向原始目录并记录原文件和压缩文件哈希，包含全部进程命令、PID、退出码、采样、发送确认和逐观众位图；不是仅凭服务端send次数证明收齐。

```powershell
cd D:\coding\DanmakuLab
pwsh -NoProfile -File scripts/run/hotspot-workers.ps1
```

结果目录不能复用。默认不带RunFormal只运行全部工具验收和四轮短对照；正式轮遇到失败立即停下，绝不自动重试补齐。

本次没有修改Java业务。拆分后若延迟降低，首先说明压测器开销发生变化；没有额外因果证据，不能写成服务端优化、容量翻倍或旧故障已修复。
