#requires -Version 7.0
<#
修复超时后的 direct 与 buffered 四组对照。要求两个同版本服务已启动。
依次运行 direct-normal、direct-paused、buffered-normal、buffered-paused，始终只一组施压。
每秒采集业务与邮箱指标、双端进程 CPU 累计秒和工作集；保留每轮独立日志与失败结果。
#>
param(
    [string]$DirectUrl = 'http://127.0.0.1:8081',
    [string]$BufferedUrl = 'http://127.0.0.1:8082',
    [ValidateRange(1,900)][int]$Seconds = 90,
    [ValidateRange(1,40)][int]$Rate = 40,
    [ValidateRange(10,100)][int]$Viewers = 10,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ProcessEvidence.psm1') -Force
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $root
try {
    if (-not $OutputDirectory) {
        $beijing = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'China Standard Time')
        $OutputDirectory = "docs/results/$($beijing.ToString('yyyy-MM-dd'))/slow-compare-$($beijing.ToString('HHmmss'))"
    }
    if (Test-Path -LiteralPath $OutputDirectory) { throw '输出目录已存在，请为新实验使用新目录。' }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $output = (Resolve-Path $OutputDirectory).Path
    function Snapshot([string]$Url) {
        $index = Invoke-RestMethod "$Url/actuator/metrics" -TimeoutSec 3
        $values = @($index.names | Where-Object {
            $_ -like 'lab.*' -or $_ -in @('jvm.threads.live','process.cpu.usage','jvm.gc.pause')
        } | ForEach-Object { Invoke-RestMethod "$Url/actuator/metrics/$_" -TimeoutSec 3 })
        $values += Invoke-RestMethod "$Url/actuator/metrics/jvm.memory.used?tag=area:heap" -TimeoutSec 3
        [ordered]@{ at = [DateTimeOffset]::UtcNow.ToString('o'); metrics = $values }
    }
    function Process-Sample([Diagnostics.Process]$Item) {
        try {
            $Item.Refresh()
            @{ pid=$Item.Id; cpuSeconds=$Item.TotalProcessorTime.TotalSeconds; workingSetBytes=$Item.WorkingSet64 }
        } catch { $null }
    }
    # 同一目录固定归档源码/JAR 标识；不修改历史实验的哈希来迎合新版代码。
    $manifest = @(Get-Item pom.xml) + @(Get-ChildItem src,scripts -Recurse -File | Where-Object { $_.FullName -notmatch '__pycache__' })
    @($manifest | Sort-Object FullName | ForEach-Object {
        @{ path=[IO.Path]::GetRelativePath($root,$_.FullName); sha256=(Get-FileHash -LiteralPath $_.FullName).Hash }
    }) | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $output 'source-sha256.json') -Encoding utf8
    Get-FileHash target/danmaku-lab-0.1.0-SNAPSHOT.jar | ConvertTo-Json |
        Set-Content (Join-Path $output 'jar-sha256.json') -Encoding utf8
    @{ os=[Environment]::OSVersion.VersionString; cpu=(Get-CimInstance Win32_Processor).Name;
       logicalProcessors=[Environment]::ProcessorCount; python=(& python --version); java=((& java -version 2>&1)|Out-String);
       seconds=$Seconds; rate=$Rate; viewers=$Viewers; cpuUnit='Cumulative process CPU seconds, not percent';
       note='Same-host loopback, sequential four groups; JVMs share machine resources.' } |
        ConvertTo-Json -Depth 4 | Set-Content (Join-Path $output 'environment.json') -Encoding utf8
    foreach ($serverMode in @('direct','buffered')) {
        $url = $(if ($serverMode -eq 'direct') { $DirectUrl } else { $BufferedUrl }).TrimEnd('/')
        if (-not ([Uri]$url).IsLoopback) { throw '本入口只用于同机回环实验。' }
        $mode = Invoke-RestMethod "$url/actuator/metrics/lab.outbound.mode.buffered"
        $expectedMode = $(if ($serverMode -eq 'buffered') { 1 } else { 0 })
        if ($mode.measurements[0].value -ne $expectedMode) { throw "服务模式错误：$url" }
        $listener = Get-NetTCPConnection -LocalPort ([Uri]$url).Port -State Listen | Select-Object -First 1
        $server = Get-Process -Id $listener.OwningProcess
        foreach ($clientMode in @('normal','paused')) {
            $run = Join-Path $output "$serverMode-$clientMode"
            # Python 自己创建数据目录；编排日志放在父目录，避免 exist_ok=False 冲突。
            $prefix = Join-Path $output "$serverMode-$clientMode"
            $before = Snapshot $url
            $before | ConvertTo-Json -Depth 12 | Set-Content "$prefix-before.json" -Encoding utf8
            if (($before.metrics | Where-Object name -eq 'lab.connections').measurements[0].value -ne 0) {
                throw '实验前服务仍有连接，请先关闭演示页面的连接或等待上轮清理。'
            }
            (Get-CimInstance Win32_Process -Filter "ProcessId=$($server.Id)").CommandLine |
                Set-Content "$prefix-server-command.txt" -Encoding utf8
            $info = [Diagnostics.ProcessStartInfo]::new()
            $info.FileName = (Get-Command python).Source
            $info.WorkingDirectory = $root
            $info.UseShellExecute = $false
            $info.CreateNoWindow = $true
            $info.RedirectStandardOutput = $true
            $info.RedirectStandardError = $true
            $info.Environment['PYTHONUTF8'] = '1'
            $arguments = @('scripts/load/slow-consumer.py','--mode',$clientMode,'--url',(($url -replace '^http','ws')+'/ws'),
                '--seconds',"$Seconds",'--rate',"$Rate",'--viewers',"$Viewers",'--output',$run)
            foreach ($argument in $arguments) { $info.ArgumentList.Add($argument) }
            "python $($arguments -join ' ')" | Set-Content "$prefix-command.txt" -Encoding utf8
            # 可变对象由采样回调和外层共同读取；不用回调局部变量冒充已更新的累计错误数。
            $sampling = [pscustomobject]@{ Next = 0.0; Errors = 0 }
            $runError = $null
            $status = [ordered]@{ collectionErrors = 0; connectionsAfter = $null; bufferedBytesAfter = $null; slotsAfter = $null }
            try {
                Write-Output "Started $serverMode-$clientMode; data: $run"
                $processStatus = Invoke-ProcessWithEvidence -StartInfo $info -Prefix $prefix -TimeoutSeconds ($Seconds + 60) -OnTick {
                    param($child, $elapsed)
                    if ($elapsed -ge $sampling.Next) {
                        $sample = [ordered]@{ elapsed=$elapsed; server=Process-Sample $server; client=Process-Sample $child }
                        try { $sample.snapshot = Snapshot $url }
                        catch { $sample.error=$_.Exception.Message; $sampling.Errors++ }
                        $sample | ConvertTo-Json -Compress -Depth 14 | Add-Content "$prefix-samples.jsonl" -Encoding utf8
                        $sampling.Next = $elapsed + 1
                    }
                }
            } catch { $runError = $_ }
            finally {
                # 模块已在 finally 中终止子进程并保存文本，即使超时也继续尽力保存服务端清理证据。
                # 原错误保存在 runError；后续快照失败不能用新异常覆盖最初的失败原因。
                if (Test-Path -LiteralPath "$prefix-status.json") {
                    try {
                        $saved = Get-Content -LiteralPath "$prefix-status.json" -Raw | ConvertFrom-Json -AsHashtable
                        foreach ($key in $saved.Keys) { $status[$key] = $saved[$key] }
                    } catch { if (-not $runError) { $runError = $_ } }
                }
                $status.collectionErrors = $sampling.Errors
                Start-Sleep -Seconds 2
                try {
                    $after = Snapshot $url
                    $after | ConvertTo-Json -Depth 12 | Set-Content "$prefix-after.json" -Encoding utf8
                    $status.connectionsAfter = ($after.metrics | Where-Object name -eq 'lab.connections').measurements[0].value
                    $status.bufferedBytesAfter = ($after.metrics | Where-Object name -eq 'lab.outbound.bytes').measurements[0].value
                    $status.slotsAfter = ($after.metrics | Where-Object name -eq 'lab.outbound.slots').measurements[0].value
                } catch {
                    $status.afterSnapshotError = $_.Exception.Message
                    if (-not $runError) { $runError = $_ }
                }
                if ($runError) { $status.error = $runError.Exception.Message }
                try { $status | ConvertTo-Json -Depth 8 | Set-Content "$prefix-status.json" -Encoding utf8 }
                catch { if (-not $runError) { $runError = $_ } else { Write-Warning "保存状态失败：$($_.Exception.Message)" } }
            }
            if ($runError) { throw $runError }
            if ($status.exitCode -ne 0 -or $status.collectionErrors -gt 0 -or $status.connectionsAfter -ne 0 -or
                    $status.bufferedBytesAfter -ne 0 -or $status.slotsAfter -ne 0) {
                $status.error = "实验或清理失败，证据已保留：$prefix"
                $status | ConvertTo-Json -Depth 8 | Set-Content "$prefix-status.json" -Encoding utf8
                throw "实验或清理失败，证据已保留：$prefix"
            }
            try { $report = Get-Content (Join-Path $run 'summary.json') -Raw | ConvertFrom-Json }
            catch {
                # 即使客户端退出码为零，缺失/损坏汇总也属于失败，状态文件必须留下具体原因。
                $summaryError = $_
                $status.error = $_.Exception.Message
                try { $status | ConvertTo-Json -Depth 8 | Set-Content "$prefix-status.json" -Encoding utf8 }
                catch { Write-Warning "保存汇总失败状态时出错：$($_.Exception.Message)" }
                throw $summaryError
            }
            Write-Output "$serverMode-${clientMode}: accepted=$($report.accepted), normal=$($report.normalReceived)/$($report.normalExpected), p99=$($report.normalLatencyMs.p99)ms"
        }
    }
    Write-Output "四组已收尾。退出成功不代表性能目标通过，请结合拒绝、缺口、延迟和采样分析：$output"
} finally {
    Pop-Location
}
