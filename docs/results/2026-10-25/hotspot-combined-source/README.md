# 拆分前压测源码快照

保存修改前的原始文件字节，供历史 manifest 校验；扩展名加 `.txt`，避免被当成当前运行入口。

| 文件 | 原始路径 | SHA256 |
| --- | --- | --- |
| hotspot.js.txt | scripts/load/hotspot.js | C42FD3258CF340C86C201D7625255AECDC7C12E6114D2FBDEA61B50E5BE0E236 |
| hotspot.ps1.txt | scripts/run/hotspot.ps1 | 127097F54CCE3ABB88645909A7A5E6B988ED6EBE830AC487F72E166A2EB13CDB |

对应此前千连接结果与用户 2026-10-25 的 125332/130643 记录。更早的 hotspot-tool-smoke 发生在补充采样计数等修改前，以它自身的 manifest 为准，不能宣称本快照覆盖所有历史版本。
