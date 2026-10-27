# fair 改造前的双进程工具源码

保留拆分版原始字节，供 `hotspot-split-smoke`、`hotspot-split-1000`、`hotspot-212742` 等历史 manifest 校验。当前 runner 已新增 DispatchMode 核对，不能用当前文件哈希覆盖历史值。

`hotspot.ps1.txt` 的 SHA256 为 `C12E3B733D840CDF3FB4DF8D5943280EF4DAD9D4B53E54B95D07F0800479A739`，已与历史 manifest 验证相同；`hotspot.js.txt` 为这几轮使用的同一脚本。更早的单进程版本见 [另一份快照](../hotspot-combined-source/README.md)。
