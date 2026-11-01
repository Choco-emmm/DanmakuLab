# 前置工具验证与显示修正

20连接T3/T5和1000连接T3/T5短测均通过，结构化轮次、接收计数、JAR身份和12项清理记录正确。

控制台轮次名因PowerShell大小写不敏感，被foreach的$name覆盖了参数$Name，显示成lab.dispatch.ready；已用真实AST回归先复现再修正，后续改用独立gaugeName及结构化row.name。正式暂停标记在短测期间写入，随后源码冻结保护结束本批；0轮正式执行，不是正式性能失败或自动重试补成绩。

红/绿日志保存在longrun-tool-verification，修正后的全新批次为longrun-repeat-02，重新做前置验收后才正式。原始日志不覆盖。
