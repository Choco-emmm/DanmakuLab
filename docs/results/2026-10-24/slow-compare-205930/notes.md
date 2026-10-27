# 用户操作复测：真实 TCP 停读与有界输出（2026-10-24）

用户亲手启动两个服务并运行 `pwsh -NoProfile -File scripts/run/slow-compare.ps1`，本目录保存此次独立复测。每组 10 个正常观众和 1 个探针，输入 40 条/秒、90 秒，共提交 3600 条输入；正常观众的全部输入投递需求为 36,000 次。

## 客户端结果

| 服务 / 探针读取方式 | 接受输入 | 拒绝输入 | 正常观众收到 / 全部输入投递需求 | P95 / P99 ms | 最大延迟 ms |
| --- | --- | --- | --- | --- | --- |
| direct / normal | 3600 | 0 | 36,000 / 36,000 | 2 / 2 | 6 |
| direct / paused | 3449 | 151 | 34,490 / 36,000 | 2 / 4236 | 5065 |
| buffered / normal | 3600 | 0 | 36,000 / 36,000 | 2 / 2 | 5 |
| buffered / paused | 3600 | 0 | 36,000 / 36,000 | 2 / 2 | 4 |

direct-paused 的 151 条拒绝全部为 `connection_rate_limit`，对应 1510 次未投递需求；正常观众收齐了全部已接受输入，不能把输入拒绝当作已接受消息在网络中丢失。
四组正常观众的重复、乱序和连接错误均为 0；停读探针本身的未接收与断连单独记录，不从正常观众分母剔除连接。

## 制品、清理与限制

- [JAR 哈希](jar-sha256.json)与[首轮制品](../slow-compare-first/jar-sha256.json)一致：`9C45A969CCA6D9BA99D01A538FA4E327EDF934D17AF93A0A280D53A0696A76E6`。两个模式使用同一有效 5 秒同步发送超时。
- 四组进程退出码和采集错误均为 0；结束后连接数、输出预算字节和关闭名额均为 0。
- 本轮为同机 Windows 回环实验，单个探针停止读取 socket，不等同于所有低带宽或断网场景；两轮结果分别保留，不合并为一个延迟分布。
- 当前证据支持小规模单停读探针下的机制改进，不代表正式千连接混合慢消费者、15 分钟或 30 分钟验收已完成。
- 编排脚本将子进程标准输出收集到文件，每组结束才打印汇总；中途控制台安静不代表卡住。运行进度可结合 `*-samples.jsonl` 的采样记录和进程状态判断。

## 原始证据

- 客户端汇总：[direct-normal](direct-normal/summary.json)、[direct-paused](direct-paused/summary.json)、[buffered-normal](buffered-normal/summary.json)、[buffered-paused](buffered-paused/summary.json)。
- 清理状态：[direct-normal](direct-normal-status.json)、[direct-paused](direct-paused-status.json)、[buffered-normal](buffered-normal-status.json)、[buffered-paused](buffered-paused-status.json)。
- [环境与参数](environment.json)、[启动时源码哈希](source-sha256.json)、[直发停读采样](direct-paused-samples.jsonl)、[缓冲停读采样](buffered-paused-samples.jsonl)。
- [当天实现与首轮报告](../outbound-report.md)、[实现说明](../../../BOUNDED_OUTBOUND.md)。
