# Redis实现与单机基线归档

本轮在修改Redis代码前运行了单机65项Java测试，全部通过，结果为baseline-tests.txt。baseline-source.json保存59个源码/配置/脚本的SHA256，baseline-jar.json保存旧制品标识。

完整基线源码和旧JAR已从target快照转存到项目根目录`.lab-artifacts/baselines/pre-redis-20261026.zip`，其路径、体积、压缩包哈希及逐文件校验数见 [长期归档索引](baseline-archive.json)。该目录独立于target，不受mvn clean影响；按.gitignore仅保留本地，不把大二进制放进Git。旧单机JAR哈希为`63C3F3BF6F415E0165FDA5EE710ACDFCA896D8E96981A0539314DD88C4994E28`，原始单机实验目录不改写。

Redis实现首轮75项测试通过；增加远端调度预算与来源身份测试后，最终77项普通测试通过。显式redis-integration profile又运行了1项真实Redis测试，连接失败将失败、不跳过，见 [verify日志](redis-integration.txt)。[早期单元日志](redis-tests.txt)保留，不将75写成77。打包时一次PowerShell属性未加引号导致命令失败，原错误redis-package.txt与修正日志redis-package-fixed.txt均保留。

本轮另有6项Python离线统计测试。真实网络验收与图表见 [总报告](../redis-report.md)。ui-verification.json记录真实双页面跨节点单次接收、来源标签、Redis暂停产生unknown、换房隔离和375px无横向溢出。ui-mobile.png已查看；浏览器最初已知路径失效，改用本机已安装的chromium-1217完成验证，没有安装新浏览器。

analyze.py.txt只读原始数据进行独立复核、生成资源图和raw-sha256.json。原始manifest保留运行时内容；后来客户端增加故障探针与失败归档，当前工具哈希与早期轮次不完全相同，verification.json逐项列明，不伪造“所有源码仍一致”。正式网络轮的Java业务实现均使用同一个Redis测试制品。

当前实现、工具、文档和归档均未提交推送，不把本地完成写成已发布。单机基线和Redis源代码/制品索引长期本地保存，后续按用户授权再整理Git发布。
