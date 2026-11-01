# 子进程回归夹具的启动竞争

原测试在0.5秒后注入采样异常，假定Python已执行两个flush打印。本次归档中状态显示原故障正确保留、管道读取完成且没有捕获错误，但两个输出为空：子进程在执行打印之前就可能被杀死，不能称为生产捕获模块丢失了“已经打印”的内容。

修改`scripts/tests/process-evidence.test.ps1`：真实子进程先flush两路诊断，再写本测试独有的就绪文件，回调看到文件才注入异常。整体仍有5秒截止，不改变生产模块的超时与归档逻辑。修改后5个场景通过，命令：

```powershell
pwsh -NoProfile -File scripts/tests/process-evidence.test.ps1
```

此目录保留原始失败状态和空输出，不用修复后的结果覆盖。
