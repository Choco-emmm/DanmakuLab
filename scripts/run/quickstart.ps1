#requires -Version 7.0
<#
一键演示入口：构建（必要时）、启动服务、等待健康检查、打开演示页，并在退出时清理自己启动的 JVM。
主要面向 Windows 10/11 + PowerShell 7；Linux/WSL 的停读实验入口见 docs/LINUX_IO.md。

为什么需要它：
- 手工演示要同时记住 JAR 路径、端口、输出模式和停止方式，容易漏掉旧进程。
- 本脚本只管理自己启动的进程；端口被占用时直接拒绝，不杀别人的服务。
- direct 模式用于看最小路径；fair 模式同时开启 buffered 和 fair，适合演示两层队列。

示例：
  pwsh -NoProfile -File scripts/run/quickstart.ps1
  pwsh -NoProfile -File scripts/run/quickstart.ps1 -Mode fair -Port 8081
  pwsh -NoProfile -File scripts/run/quickstart.ps1 -NoBrowser -RunSeconds 5
#>
[CmdletBinding()]
param(
    # 演示页和 Actuator 共用的本地端口；默认与 application.yml 一致。
    [ValidateRange(1, 65535)][int]$Port = 8080,

    # direct：默认基线；buffered：连接邮箱；fair：在 buffered 之上启用房间入口与批次轮转。
    [ValidateSet('direct', 'buffered', 'fair')][string]$Mode = 'direct',

    # 默认使用 mvn verify 生成的工件；要复现归档实验可显式指向 release JAR。
    [string]$JarPath = 'target/danmaku-lab-0.1.0-SNAPSHOT.jar',

    # 强制重新打包；默认只在 JAR 不存在时构建，避免每次演示都等测试/打包。
    [switch]$Build,

    # 自动化或远程终端里不打开浏览器。
    [switch]$NoBrowser,

    # 仅供自动化冒烟：等待指定秒数后停止；0 表示一直运行到 Ctrl+C。
    [ValidateRange(0, 3600)][int]$RunSeconds = 0
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $root
$server = $null
$listenerPid = $null
$logPath = $null
try {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw '找不到 java，请先安装 JDK 21+ 并把 java 加入 PATH。'
    }
    # Oracle 的 javapath\java.exe 可能只是启动器，会再拉起真正的 JVM；
    # 优先使用 JAVA_HOME\bin\java.exe，避免停止时只停到启动器而漏掉子进程。
    $javaCommand = (Get-Command java).Source
    if ($env:JAVA_HOME) {
        $javaHomeExe = Join-Path $env:JAVA_HOME 'bin/java.exe'
        if (Test-Path -LiteralPath $javaHomeExe) { $javaCommand = $javaHomeExe }
    }
    $jarExists = Test-Path -LiteralPath $JarPath
    if (-not $jarExists -and -not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw '找不到 mvn；请先安装 Maven 3.9+，或手动准备 JAR 后用 -JarPath 指定。'
    }

    # 只构建缺的 JAR；-DskipTests 是“为了尽快演示”，正式验证仍应运行 mvn verify。
    if ($Build -or -not $jarExists) {
        Write-Host "[quickstart] 构建 $JarPath ..."
        & mvn -B -ntp -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败。' }
    }
    $jar = (Resolve-Path -LiteralPath $JarPath).Path

    # 端口被占用时绝不擅自结束旧进程；让使用者自己决定是否停掉旧服务。
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
        throw "端口 $Port 已被占用；请停止旧服务，或用 -Port 指定其他端口。"
    }

    # fair 依赖 buffered，和 README / 配置校验保持一致；direct 不追加任何参数。
    $modeArgs = @()
    switch ($Mode) {
        'buffered' {
            $modeArgs = @('--lab.outbound.mode=buffered')
        }
        'fair' {
            $modeArgs = @('--lab.outbound.mode=buffered', '--lab.dispatch.mode=fair')
        }
    }

    $logDirectory = Join-Path $root 'load-results'
    New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $logPath = Join-Path $logDirectory "quickstart-$stamp.log"
    $errPath = Join-Path $logDirectory "quickstart-$stamp.err.log"

    # -RedirectStandardOutput 让服务日志留在 load-results，便于演示失败后直接查看。
    $javaArgs = @('-Xms128m', '-Xmx256m', '-jar', $jar, "--server.port=$Port") + $modeArgs
    Write-Host "[quickstart] 启动 $jar（$Mode，端口 $Port）"
    $server = Start-Process -FilePath $javaCommand -ArgumentList $javaArgs -WorkingDirectory $root `
        -RedirectStandardOutput $logPath -RedirectStandardError $errPath -PassThru -WindowStyle Hidden

    # 健康检查代替固定 sleep：只有 /actuator/health 返回 UP 才认为可以打开页面。
    $deadline = (Get-Date).AddSeconds(45)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        if ($server.HasExited) { break }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { $healthy = $true; break }
        } catch {
            # 启动早期连接会被拒绝，属于正常现象；继续轮询到截止时间。
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $healthy) {
        $tail = if (Test-Path -LiteralPath $logPath) { Get-Content -LiteralPath $logPath -Tail 20 } else { @() }
        throw "服务未在 45 秒内通过健康检查。`n$($tail -join [Environment]::NewLine)"
    }

    # 以端口监听者作为真正的 JVM PID；启动器进程可能已经退出，不能只依赖 Start-Process 返回的 PID。
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) { $listenerPid = [int]$listener.OwningProcess }

    $displayPid = if ($listenerPid) { $listenerPid } else { $server.Id }
    $url = "http://127.0.0.1:$Port/"
    Write-Host "[quickstart] 演示地址：$url"
    Write-Host "[quickstart] Java PID：$displayPid"
    Write-Host "[quickstart] 日志：$logPath"
    Write-Host '[quickstart] 按 Ctrl+C 停止服务。'

    # 默认打开系统浏览器；-NoBrowser 供 CI、远程终端或截图脚本使用。
    if (-not $NoBrowser) {
        if ($IsWindows) { Start-Process $url }
        elseif ($IsMacOS) { & open $url }
        else { & xdg-open $url }
    }

    if ($RunSeconds -gt 0) {
        Write-Host "[quickstart] $RunSeconds 秒后自动停止（自动化检查模式）。"
        Start-Sleep -Seconds $RunSeconds
    } else {
        # 保持脚本存活；Ctrl+C 中止时进入 finally，清理真正的 JVM（监听端口的进程）。
        $waitPid = if ($listenerPid) { $listenerPid } else { $server.Id }
        try { Wait-Process -Id $waitPid -ErrorAction Stop } catch { }
    }
} finally {
    # 只停止本脚本创建的进程；启动器和真正 JVM 可能同时存在，去重后都尝试停止。
    $stopIds = @()
    if ($listenerPid) { $stopIds += $listenerPid }
    if ($server) { $stopIds += $server.Id }
    foreach ($processId in ($stopIds | Select-Object -Unique)) {
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($process) {
            Write-Host "[quickstart] 停止 JVM PID $processId ..."
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
    }
    if ($server) {
        try { $server.WaitForExit(5000) | Out-Null } catch { }
    }
    Pop-Location
}
