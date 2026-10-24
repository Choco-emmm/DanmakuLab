#requires -Version 7.0
<#
T1 独立运行与证据采集入口。连接数由 Connections 决定，默认 100。
服务须已启动；本脚本不修改后端配置、不覆盖历史结果，失败也会保存证据。
默认 60 秒预热、180 秒测量、每秒 5 条输入；总时长还包括建连与排空。
#>
param(
    [ValidateRange(1, 2000)][int]$Connections = 100,
    [ValidateRange(0, 300)][int]$WarmupSeconds = 60,
    [ValidateRange(1, 600)][int]$DurationSeconds = 180,
    [ValidateRange(1, 20)][int]$Rate = 5,
    [string]$HttpUrl = 'http://127.0.0.1:8080',
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $projectRoot
$k6Process = $null
try {
    $baseUrl = $HttpUrl.TrimEnd('/')
    if ((Invoke-RestMethod "$baseUrl/actuator/health" -TimeoutSec 5).status -ne 'UP') {
        throw '请先启动服务，健康检查必须为 UP。'
    }
    if (-not $OutputDirectory) {
        $beijing = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'China Standard Time')
        $OutputDirectory = "docs/results/$($beijing.ToString('yyyy-MM-dd'))/t1-$Connections-$($beijing.ToString('HHmmss'))"
    }
    if (Test-Path -LiteralPath $OutputDirectory) { throw "结果目录已存在，拒绝覆盖：$OutputDirectory" }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $outputPath = (Resolve-Path -LiteralPath $OutputDirectory).Path
    $wsUrl = ($baseUrl -replace '^http', 'ws') + '/ws'
    # 新服务显式报告模式；旧基线没有此指标时记 unreported，不能猜成 direct。
    $broadcastMode = 'unreported'
    $modeIndex = Invoke-RestMethod "$baseUrl/actuator/metrics" -TimeoutSec 5
    if ($modeIndex.names -contains 'lab.outbound.mode.buffered') {
        $modeSample = Invoke-RestMethod "$baseUrl/actuator/metrics/lab.outbound.mode.buffered" -TimeoutSec 5
        $broadcastMode = $(if ($modeSample.measurements[0].value -eq 1) { 'buffered' } else { 'direct' })
    }

    # 顺序采集的一组指标不是同一纳秒的快照，因此保留起止时间和采集耗时。
    # 每 5 秒采样一次，不可把采样最大值称为整个运行期间的绝对峰值。
    function Read-ServerMetrics {
        $began = [DateTimeOffset]::UtcNow
        $index = Invoke-RestMethod "$baseUrl/actuator/metrics" -TimeoutSec 5
        $names = @($index.names | Where-Object {
            $_ -like 'lab.*' -or $_ -in @('jvm.threads.live', 'jvm.gc.pause', 'process.cpu.usage')
        })
        $samples = @($names | ForEach-Object {
            Invoke-RestMethod "$baseUrl/actuator/metrics/$_" -TimeoutSec 5
        })
        # 单独按 heap 标签查询，避免把堆和非堆内存加在一起称为 JVM 堆。
        $samples += Invoke-RestMethod "$baseUrl/actuator/metrics/jvm.memory.used?tag=area:heap" -TimeoutSec 5
        [ordered]@{
            startedAt = $began.ToString('o')
            endedAt = [DateTimeOffset]::UtcNow.ToString('o')
            collectionMs = ([DateTimeOffset]::UtcNow - $began).TotalMilliseconds
            metrics = $samples
        }
    }

    function Process-Sample([System.Diagnostics.Process]$Process) {
        try {
            $Process.Refresh()
            [ordered]@{
                pid = $Process.Id
                cpuSeconds = $Process.TotalProcessorTime.TotalSeconds
                workingSetBytes = $Process.WorkingSet64
                threads = $Process.Threads.Count
            }
        } catch {
            # 采样与退出可能同时发生；null 表示未取得样本，不能当成资源归零。
            $null
        }
    }

    # 仅在地址为本机回环时获取监听进程；远端服务的资源必须由远端另采，不能冒用本机数值。
    $serverProcess = $null
    $serverCommandLine = $null
    $uri = [Uri]$baseUrl
    if ($uri.IsLoopback) {
        $listener = Get-NetTCPConnection -LocalPort $uri.Port -State Listen | Select-Object -First 1
        $serverProcess = Get-Process -Id $listener.OwningProcess
        $serverCommandLine = (Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)").CommandLine
    }
    $cpu = Get-CimInstance Win32_Processor
    $machine = Get-CimInstance Win32_ComputerSystem
    [ordered]@{
        capturedAt = [DateTimeOffset]::UtcNow.ToString('o')
        os = [Environment]::OSVersion.VersionString
        cpu = $cpu.Name.Trim()
        logicalProcessors = $cpu.NumberOfLogicalProcessors
        physicalMemoryBytes = $machine.TotalPhysicalMemory
        java = ((& java -version 2>&1) | Out-String).Trim()
        k6 = ((& k6 version) | Out-String).Trim()
        target = $baseUrl
        serverCommandLine = $serverCommandLine
        sourceCommit = (& git -c safe.directory=D:/coding/DanmakuLab rev-parse HEAD | Out-String).Trim()
        sourceStatus = (& git -c safe.directory=D:/coding/DanmakuLab status --short | Out-String).Trim()
        note = 'Loopback client and server share CPU/RAM; one k6 VU coordinates sockets. Resource maxima are sampled, not absolute peaks.'
    } | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $outputPath 'environment.json') -Encoding utf8
    [ordered]@{
        scenario = 'T1'; mode = $broadcastMode; connections = $Connections
        warmupSeconds = $WarmupSeconds; durationSeconds = $DurationSeconds
        targetInputPerSecond = $Rate; requestJsonBytes = 256
        broadcastJsonBytes = 'Measured in k6 summary; larger than input JSON'
        expectedMeasuredDeliveries = $Connections * $Rate * $DurationSeconds
        latencySampleEvery = 10; resourceSampleSeconds = 5; drainSeconds = 5
    } | ConvertTo-Json | Set-Content (Join-Path $outputPath 'config.json') -Encoding utf8
    $sourceFiles = @(Get-Item pom.xml) + @(Get-ChildItem src,scripts -Recurse -File)
    $manifest = @($sourceFiles | Sort-Object FullName | ForEach-Object {
        @{ path = [IO.Path]::GetRelativePath($projectRoot, $_.FullName).Replace('\', '/')
           sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    })
    ConvertTo-Json -InputObject $manifest -Depth 4 | Set-Content (Join-Path $outputPath 'source-sha256.json') -Encoding utf8
    if (Test-Path 'target/danmaku-lab-0.1.0-SNAPSHOT.jar') {
        # 是否实际运行这个 JAR 仍以 serverCommandLine 为准；保留哈希方便复核。
        Get-FileHash target/danmaku-lab-0.1.0-SNAPSHOT.jar -Algorithm SHA256 |
            ConvertTo-Json | Set-Content (Join-Path $outputPath 'jar-sha256.json') -Encoding utf8
    }

    $before = Read-ServerMetrics
    $before | ConvertTo-Json -Depth 15 | Set-Content (Join-Path $outputPath 'server-before.json') -Encoding utf8
    # ArgumentList 逐项传参，不通过 shell 拼接命令；路径有空格也不会拆成多个参数。
    # 分别异步读取 stdout/stderr，避免某个管道塞满后造成工具进程相互等待。
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = (Get-Command k6).Source
    $startInfo.WorkingDirectory = $projectRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Environment['K6_WEB_DASHBOARD'] = 'false'
    $arguments = @('run', '--quiet', '-e', "CONNECTIONS=$Connections", '-e', "WARMUP_SECONDS=$WarmupSeconds",
        '-e', "DURATION_SECONDS=$DurationSeconds", '-e', "RATE=$Rate", '-e', "WS_URL=$wsUrl",
        '-e', "SUMMARY_PATH=$outputPath/k6-summary.json", 'scripts/load/baseline.js')
    foreach ($argument in $arguments) { $startInfo.ArgumentList.Add($argument) }
    "k6 $($arguments -join ' ')" | Set-Content (Join-Path $outputPath 'commands.txt') -Encoding utf8
    $k6Process = [Diagnostics.Process]::new()
    $k6Process.StartInfo = $startInfo
    $null = $k6Process.Start()
    $stdout = $k6Process.StandardOutput.ReadToEndAsync()
    $stderr = $k6Process.StandardError.ReadToEndAsync()
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $nextSample = 0
    $collectionErrors = 0
    Write-Output "T1 started: $Connections connections; evidence: $outputPath"
    while (-not $k6Process.HasExited) {
        if ($timer.Elapsed.TotalSeconds -ge $nextSample) {
            $sample = [ordered]@{
                capturedAt = [DateTimeOffset]::UtcNow.ToString('o')
                elapsedSeconds = $timer.Elapsed.TotalSeconds
                client = Process-Sample $k6Process
                server = $(if ($serverProcess) { Process-Sample $serverProcess } else { $null })
            }
            try { $sample.serverMetrics = Read-ServerMetrics } catch {
                $sample.collectionError = $_.Exception.Message
                $collectionErrors++
            }
            $sample | ConvertTo-Json -Depth 16 -Compress |
                Add-Content (Join-Path $outputPath 'samples.jsonl') -Encoding utf8
            $nextSample = $timer.Elapsed.TotalSeconds + 5
        }
        Start-Sleep -Milliseconds 200
    }
    $k6Process.WaitForExit()
    $exitCode = $k6Process.ExitCode
    $stdout.GetAwaiter().GetResult() | Set-Content (Join-Path $outputPath 'k6-stdout.txt') -Encoding utf8
    $stderr.GetAwaiter().GetResult() | Set-Content (Join-Path $outputPath 'k6-stderr.txt') -Encoding utf8
    Get-Content (Join-Path $outputPath 'k6-stdout.txt')
    # 留两秒给后端关闭回调清理 Registry，再采最后的房间/连接数。
    Start-Sleep -Seconds 2
    $after = Read-ServerMetrics
    $after | ConvertTo-Json -Depth 15 | Set-Content (Join-Path $outputPath 'server-after.json') -Encoding utf8
    # 业务计数是进程累计值，必须先减去运行前数值；本轮目标最好使用独立服务端口。
    # 若其他页面同时发送，服务端全局差值可能被污染，这也不能冒充计数吻合。
    function Metric-Value($Snapshot, [string]$Name, [string]$Statistic = 'VALUE', [switch]$AllowAbsent) {
        $entry = $Snapshot.metrics | Where-Object name -eq $Name | Select-Object -First 1
        # Micrometer 事件计数器常在首个事件时创建，尚未发生的计数可显式允许不存在。
        # 连接/房间 gauge 与资源指标必须存在；不能用“未知”冒充 0 并让清理校验通过。
        if (-not $entry) {
            if ($AllowAbsent) { return 0.0 }
            throw "Required metric missing: $Name"
        }
        $measurement = $entry.measurements | Where-Object statistic -eq $Statistic | Select-Object -First 1
        if (-not $measurement -or $null -eq $measurement.value) { throw "Missing $Statistic value for metric: $Name" }
        [double]$measurement.value
    }
    $delta = [ordered]@{}
    foreach ($name in @('lab.messages.input', 'lab.messages.accepted', 'lab.messages.rejected',
        'lab.deliveries.expected', 'lab.deliveries.submitted', 'lab.writes.failed')) {
        $delta[$name] = (Metric-Value $after $name 'COUNT' -AllowAbsent) - (Metric-Value $before $name 'COUNT' -AllowAbsent)
    }
    $plannedInputs = ($WarmupSeconds + $DurationSeconds) * $Rate
    $serverCheck = $delta['lab.messages.input'] -eq $plannedInputs -and
        $delta['lab.messages.accepted'] -eq $plannedInputs -and $delta['lab.messages.rejected'] -eq 0 -and
        $delta['lab.deliveries.expected'] -eq $plannedInputs * $Connections -and
        $delta['lab.deliveries.submitted'] -eq $plannedInputs * $Connections -and $delta['lab.writes.failed'] -eq 0
    $cleanupCheck = (Metric-Value $after 'lab.connections') -eq (Metric-Value $before 'lab.connections') -and
        (Metric-Value $after 'lab.rooms') -eq (Metric-Value $before 'lab.rooms')
    if ($broadcastMode -eq 'buffered') {
        foreach ($name in @('lab.outbound.bytes', 'lab.outbound.messages', 'lab.outbound.slots')) {
            $cleanupCheck = $cleanupCheck -and (Metric-Value $after $name) -eq (Metric-Value $before $name)
        }
    }
    [ordered]@{ counters = $delta; serverCountersMatch = $serverCheck; cleanupReturnedToStart = $cleanupCheck } |
        ConvertTo-Json | Set-Content (Join-Path $outputPath 'server-delta.json') -Encoding utf8

    # CPU 以相邻样本的进程累计 CPU 秒差 / 实际间隔 / 逻辑核数计算，百分比为整机归一化值。
    # 例如 32 逻辑核机器中持续占满一个核约为 3.125%；不能把它解释为单核占用。
    $samples = @(Get-Content (Join-Path $outputPath 'samples.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
    $resources = [ordered]@{ sampleCount = $samples.Count; collectorErrors = $collectionErrors }
    foreach ($which in @('client', 'server')) {
        $cpuMax = 0.0
        for ($i = 1; $i -lt $samples.Count; $i++) {
            if ($samples[$i].$which -and $samples[$i - 1].$which) {
                $seconds = $samples[$i].elapsedSeconds - $samples[$i - 1].elapsedSeconds
                $cpuPercent = 100 * ($samples[$i].$which.cpuSeconds - $samples[$i - 1].$which.cpuSeconds) /
                    $seconds / $cpu.NumberOfLogicalProcessors
                $cpuMax = [Math]::Max($cpuMax, $cpuPercent)
            }
        }
        $resources[$which] = [ordered]@{
            sampledMaxCpuPercentOfMachine = $cpuMax
            sampledMaxWorkingSetBytes = ($samples.$which.workingSetBytes | Measure-Object -Maximum).Maximum
        }
    }
    $resources.sampledMaxHeapBytes = ($samples | ForEach-Object {
        if ($_.serverMetrics) { Metric-Value $_.serverMetrics 'jvm.memory.used' }
    } | Measure-Object -Maximum).Maximum
    $resources.sampledMaxJvmThreads = ($samples | ForEach-Object {
        if ($_.serverMetrics) { Metric-Value $_.serverMetrics 'jvm.threads.live' }
    } | Measure-Object -Maximum).Maximum
    $resources | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputPath 'resource-summary.json') -Encoding utf8
    [ordered]@{ exitCode = $exitCode; wallSeconds = $timer.Elapsed.TotalSeconds
        collectorErrors = $collectionErrors; serverCountersMatch = $serverCheck; cleanupReturnedToStart = $cleanupCheck } |
        ConvertTo-Json | Set-Content (Join-Path $outputPath 'run-status.json') -Encoding utf8
    @"
# T1 单轮记录

- 模式：$broadcastMode；连接数：$Connections；预热：$WarmupSeconds 秒；测量目标：$DurationSeconds 秒；目标输入：$Rate 条/秒。
- 服务地址：$baseUrl；是否同机、JVM 启动参数与工具版本见 environment.json。
- k6 退出码：$exitCode；服务端累计计数差值匹配：$serverCheck；连接/房间回到起点：$cleanupCheck。
- 采集错误：$collectionErrors；资源每 5 秒采样，只能报告采样最大值，不是绝对峰值。
- 完整输入 JSON 为 256 字节；完整广播大小见 k6-summary.json 的 report.broadcastBytes。
- 客户端唯一接收统计覆盖全部连接；延迟固定抽样每 10 个连接中的一个，含连接 0。
- 接收量、P50/P95/P99、实际输入速率见 k6-summary.json；CPU/RSS/堆/线程时序见 samples.jsonl。
- 本轮只验证此连接档位、此输入与此时长。不能外推为慢连接隔离、20 条/秒容量或 30 分钟稳态成绩。
- 服务端指标为整个进程的全局累计值；如有其他业务流量，差值可能被污染，必须单独解释。
"@ | Set-Content (Join-Path $outputPath 'notes.md') -Encoding utf8
    if ($exitCode -ne 0) { throw "k6 未通过，退出码 $exitCode。失败证据已保留：$outputPath" }
    if (-not $serverCheck -or -not $cleanupCheck -or $collectionErrors -gt 0) {
        throw "客户端已结束，但服务端计数、清理或采集未通过；请查看 $outputPath"
    }
    Write-Output "Passed. Evidence: $outputPath"
} finally {
    # 编排脚本自身中断时也停止它启动的 k6 子进程，避免后台继续施压。
    if ($k6Process -and -not $k6Process.HasExited) { $k6Process.Kill() }
    Pop-Location
}
