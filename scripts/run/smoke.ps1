# Windows PowerShell 7 的 T0 运行入口：要求服务已启动，负责执行 k6 和归档证据。
# Messages 控制输入条数，HttpUrl 指向服务，OutputDirectory 指定一个尚不存在的结果目录。
param(
    [ValidateRange(1, 60)][int]$Messages = 60,
    [string]$HttpUrl = 'http://127.0.0.1:8080',
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
# 根据脚本位置定位项目，从其他工作目录调用时也能找到 pom、源码与负载脚本。
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $projectRoot
try {
    if (-not $OutputDirectory) {
        # 默认按北京时间命名，避免运行主机时区不同导致结果日期混乱。
        $beijing = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'China Standard Time')
        $OutputDirectory = "docs/results/$($beijing.ToString('yyyy-MM-dd'))/smoke-$($beijing.ToString('HHmmss'))"
    }
    if (Test-Path -LiteralPath $OutputDirectory) {
        # 一次实验一个目录，拒绝覆盖旧结果，保留失败轮与成功轮的独立证据。
        throw "Output directory already exists; choose a new run directory: $OutputDirectory"
    }
    $baseUrl = $HttpUrl.TrimEnd('/')
    $health = Invoke-RestMethod "$baseUrl/actuator/health"
    if ($health.status -ne 'UP') { throw 'Server health check is not UP' }
    # http -> ws、https -> wss；后面再拼 /ws 得到实际连接端点。
    $webSocketUrl = $baseUrl -replace '^http', 'ws'
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $outputPath = (Resolve-Path -LiteralPath $OutputDirectory).Path

    # 读取 lab 业务计数以及 JVM 内存、线程数、进程 CPU 的当前快照。
    # 只有前后两次快照，不能据此声称知道运行期间的峰值或稳态内存。
    function Save-Metrics([string]$Name) {
        $index = Invoke-RestMethod "$baseUrl/actuator/metrics"
        $names = $index.names | Where-Object { $_ -like 'lab.*' -or $_ -in @('jvm.memory.used', 'jvm.threads.live', 'process.cpu.usage') }
        $samples = foreach ($metricName in $names) {
            Invoke-RestMethod "$baseUrl/actuator/metrics/$metricName"
        }
        ConvertTo-Json -InputObject @($samples) -Depth 12 | Set-Content -LiteralPath (Join-Path $outputPath $Name) -Encoding utf8
    }

    Save-Metrics 'server-before.json'
    # 记录压测机硬件与工具版本；服务器是否同机、堆大小等仍需在 notes.md 中明确填写。
    $cpu = Get-CimInstance Win32_Processor
    $machine = Get-CimInstance Win32_ComputerSystem
    $environment = [ordered]@{
        capturedAt = [DateTimeOffset]::Now.ToString('o')
        os = [Environment]::OSVersion.VersionString
        cpu = $cpu.Name.Trim()
        logicalProcessors = $cpu.NumberOfLogicalProcessors
        physicalMemoryBytes = $machine.TotalPhysicalMemory
        java = ((& java -version 2>&1) | Out-String).Trim()
        k6 = ((& k6 version) | Out-String).Trim()
        network = $baseUrl
        note = 'Client and server placement, JVM heap and other server flags must be recorded in notes.md.'
    }
    $environment | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputPath 'environment.json') -Encoding utf8
    # 理论投递 = 10 个同房间观众 × 输入条数；实际接收仍以客户端统计为准。
    @{ scenario = 'T0'; viewers = 10; isolationProbes = 1; messages = $Messages; targetInputPerSecond = 1;
       expectedUniqueDeliveries = 10 * $Messages; mode = 'direct-baseline'; wsUrl = "$webSocketUrl/ws" } |
        ConvertTo-Json | Set-Content (Join-Path $outputPath 'config.json') -Encoding utf8

    # 用逐文件 SHA-256 标识当次源码；注释和换行也影响哈希，历史清单应保留原样。
    $sourceFiles = @(Get-Item pom.xml) + @(Get-ChildItem src,scripts -Recurse -File)
    $manifest = $sourceFiles | Sort-Object FullName | ForEach-Object {
        @{ path = [IO.Path]::GetRelativePath($projectRoot, $_.FullName).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    }
    ConvertTo-Json -InputObject @($manifest) -Depth 4 | Set-Content (Join-Path $outputPath 'source-sha256.json') -Encoding utf8
    "k6 run --quiet -e MESSAGES=$Messages -e WS_URL=$webSocketUrl/ws -e SUMMARY_PATH=<run-directory>/k6-summary.json scripts/load/smoke.js" |
        Set-Content (Join-Path $outputPath 'commands.txt') -Encoding utf8

    # & 调用外部命令，反引号用于续行；-e 将参数传给 k6 的 __ENV。
    # Tee-Object 同时显示并保存输出，失败时也留下完整统计供定位。
    & k6 run --quiet -e "MESSAGES=$Messages" -e "WS_URL=$webSocketUrl/ws" `
        -e "SUMMARY_PATH=$outputPath/k6-summary.json" scripts/load/smoke.js 2>&1 |
        Tee-Object -FilePath (Join-Path $outputPath 'k6-output.txt')
    # 立即保存 k6 状态，后续采集不能覆盖“压测是否成功”的判断依据。
    $k6ExitCode = $LASTEXITCODE
    Save-Metrics 'server-after.json'
    if ($k6ExitCode -ne 0) { throw "k6 failed with exit code $k6ExitCode; evidence retained at $outputPath" }
    Write-Output "Passed. Evidence: $outputPath"
} finally {
    # 无论健康检查、压测还是文件写入是否出错，都恢复调用者原来的工作目录。
    Pop-Location
}
