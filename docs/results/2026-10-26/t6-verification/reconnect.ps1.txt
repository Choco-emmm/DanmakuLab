#requires -Version 7.4
<#
T6真实重连入口：先验证独立服务的连接上限，再启动一个有界Python客户端进程。
每秒复用HTTP会话采集指标与进程资源；预期的超额1013与计划内TCP关闭单独记账。
不把断线期间离线观众的漏收当作可靠重放能力，也不从固定在线组删除任何观众。
#>
param(
    [ValidateRange(4,500)][int]$Connections=100,
    [int]$Affected=0,
    [ValidateRange(1,5)][int]$Cycles=3,
    [ValidateRange(1,60)][int]$SteadySeconds=10,
    [ValidateRange(1,60)][int]$ChurnSeconds=10,
    [ValidateRange(1,40)][int]$Rate=20,
    [ValidateRange(5,60)][int]$RecoveryDeadline=15,
    [ValidateRange(1,5)][int]$DrainSeconds=2,
    [ValidateRange(1,500)][int]$LatencySampleEvery=10,
    [int]$Seed=20261026,
    [string]$HttpUrl='http://127.0.0.1:8086',
    [string]$OutputDirectory
)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'ProcessEvidence.psm1') -Force
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $Affected) { $Affected=[int][Math]::Floor($Connections/2) }
if ($Affected -lt 1 -or $Affected -ge $Connections) { throw 'Affected必须小于总人数且至少1。' }
$session=$null
Push-Location $root
try {
    $url=$HttpUrl.TrimEnd('/'); $uri=[Uri]$url
    if (-not $uri.IsLoopback) { throw '只支持本机回环实验。' }
    $session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
    function Read-Endpoint([string]$Path) { Invoke-RestMethod "$url$Path" -TimeoutSec 3 -WebSession $session }
    function Metric($Snapshot,[string]$Name,[switch]$Required) {
        $entry=@($Snapshot.metrics | Where-Object name -eq $Name)
        if (-not $entry.Count) {
            if ($Required) { throw "缺少必需Gauge：$Name" }
            return 0.0 # 从未发生的Counter可以尚未注册；Gauge缺失必须报错。
        }
        return [double]$entry[0].measurements[0].value
    }
    function Snapshot {
        $begin=[DateTimeOffset]::UtcNow
        $names=(Read-Endpoint '/actuator/metrics').names
        $items=@($names | Where-Object { $_ -like 'lab.*' -or $_ -in @('jvm.threads.live','jvm.gc.pause','process.cpu.usage') } |
            ForEach-Object { Read-Endpoint "/actuator/metrics/$_" })
        $items+=Read-Endpoint '/actuator/metrics/jvm.memory.used?tag=area:heap'
        @{startedAt=$begin.ToString('o');endedAt=[DateTimeOffset]::UtcNow.ToString('o');metrics=$items}
    }
    function ProcessSample([int]$ProcessId) {
        try {
            $item=Get-Process -Id $ProcessId -ErrorAction Stop
            @{pid=$item.Id;cpuSeconds=$item.TotalProcessorTime.TotalSeconds;workingSetBytes=$item.WorkingSet64;threads=$item.Threads.Count}
        } catch { $null } # 退出进程不冒充资源为零；退出码与服务端Gauge另行复核。
    }
    if ((Read-Endpoint '/actuator/health').status -ne 'UP') { throw '服务未就绪。' }
    $listener=Get-NetTCPConnection -State Listen -LocalPort $uri.Port | Select-Object -First 1
    $server=Get-Process -Id $listener.OwningProcess
    $serverCommand=(Get-CimInstance Win32_Process -Filter "ProcessId=$($server.Id)").CommandLine
    $limits=[regex]::Matches($serverCommand,'(?:^|\s)--lab\.max-connections=(\d+)(?=\s|$)')
    if ($limits.Count -ne 1 -or [int]$limits[0].Groups[1].Value -ne $Connections) {
        throw "须独立启动服务并显式指定 --lab.max-connections=$Connections，用真实满额拒绝验证准入。"
    }
    $before=Snapshot
    if ((Metric $before 'lab.connections' -Required) -ne 0 -or (Metric $before 'lab.outbound.slots' -Required) -ne 0) { throw '实验服务必须空闲。' }
    if ((Metric $before 'lab.outbound.mode.buffered' -Required) -ne 1 -or (Metric $before 'lab.dispatch.mode.fair' -Required) -ne 1) { throw '需要buffered+fair模式。' }
    $null=Metric $before 'lab.outbound.writes.inflight' -Required
    if (-not $OutputDirectory) {
        $now=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'China Standard Time')
        $OutputDirectory="docs/results/$($now.ToString('yyyy-MM-dd'))/t6-$($now.ToString('HHmmss'))"
    }
    if (Test-Path -LiteralPath $OutputDirectory) { throw '结果目录已存在，拒绝覆盖。' }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $output=(Resolve-Path -LiteralPath $OutputDirectory).Path
    $before | ConvertTo-Json -Depth 14 | Set-Content (Join-Path $output 'server-before.json') -Encoding utf8
    $serverCommand | Set-Content (Join-Path $output 'server-command.txt') -Encoding utf8
    Copy-Item src/main/resources/application.yml (Join-Path $output 'application.yml')
    $manifest=@(Get-Item pom.xml)+@(Get-ChildItem src,scripts -Recurse -File | Where-Object FullName -notmatch '__pycache__')
    @($manifest | Sort-Object FullName | ForEach-Object {@{path=[IO.Path]::GetRelativePath($root,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}}) |
        ConvertTo-Json -Depth 4 | Set-Content (Join-Path $output 'source-sha256.json') -Encoding utf8
    Get-FileHash target/danmaku-lab-0.1.0-SNAPSHOT.jar | ConvertTo-Json | Set-Content (Join-Path $output 'jar-sha256.json') -Encoding utf8
    @{scenario='T6';connections=$Connections;affected=$Affected;cycles=$Cycles;steadySeconds=$SteadySeconds;
      churnSeconds=$ChurnSeconds;rate=$Rate;recoveryDeadline=$RecoveryDeadline;drainSeconds=$DrainSeconds;seed=$Seed;
      probes=3;probeAttempts=3;maxAttempts=6;latencySampleEvery=$LatencySampleEvery;resourceSampleSeconds=1;
      outboundMode='buffered';dispatchMode='fair';inputJsonBytes=256} | ConvertTo-Json | Set-Content (Join-Path $output 'config.json') -Encoding utf8
    @{capturedAt=[DateTimeOffset]::UtcNow.ToString('o');cpu=(Get-CimInstance Win32_Processor).Name;
      logicalProcessors=[Environment]::ProcessorCount;os=[Environment]::OSVersion.VersionString;
      python=(& python --version);powershell=$PSVersionTable.PSVersion.ToString();java=((& java -version 2>&1)|Out-String).Trim();
      commit=(& git -c safe.directory=D:/coding/DanmakuLab rev-parse HEAD);
      status=((& git -c safe.directory=D:/coding/DanmakuLab status --short)|Out-String);
      note='同机回环；每秒快照非原子且不代表绝对峰值。Python模拟退避规则，非浏览器自动化。'} |
        ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'environment.json') -Encoding utf8
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=(Get-Command python).Source; $info.WorkingDirectory=$root
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.Environment['PYTHONUTF8']='1'
    $arguments=@('scripts/load/reconnect.py','--url',(($url -replace '^http','ws')+'/ws'),
        '--connections',"$Connections",'--affected',"$Affected",'--cycles',"$Cycles",'--steady-seconds',"$SteadySeconds",
        '--churn-seconds',"$ChurnSeconds",'--rate',"$Rate",'--recovery-deadline',"$RecoveryDeadline",
        '--drain-seconds',"$DrainSeconds",'--latency-sample-every',"$LatencySampleEvery",'--seed',"$Seed",'--output',(Join-Path $output 'client'))
    foreach ($argument in $arguments) { $info.ArgumentList.Add($argument) }
    @{executable=$info.FileName;arguments=$arguments} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'command.json') -Encoding utf8
    $sampling=[pscustomobject]@{Next=0.0;Progress=0.0;Errors=0;Bounds=$true;Count=0}
    $status=[ordered]@{processPassed=$false;clientPassed=$false;collectorErrors=0;sampledBoundsPassed=$false;
        cleanupReturnedToZero=$false;serverInputCountersMatch=$false;admissionCountersMatch=$false;deliveryBoundsPassed=$false;passed=$false}
    $failure=$null
    try {
        Write-Output "T6 started: $Connections viewers; $Affected reconnect per cycle; evidence: $output"
        $timeout=90+$SteadySeconds+$Cycles*($ChurnSeconds+$SteadySeconds+$RecoveryDeadline+2*$DrainSeconds)
        $null=Invoke-ProcessWithEvidence -StartInfo $info -Prefix (Join-Path $output 'python') -TimeoutSeconds $timeout -OnTick {
            param($child,$elapsed)
            if ($elapsed -ge $sampling.Progress) { Write-Host "T6 running: $([Math]::Floor($elapsed))s; cycles=$Cycles"; $sampling.Progress=$elapsed+10 }
            if ($elapsed -ge $sampling.Next) {
                $row=@{elapsedSeconds=$elapsed;server=ProcessSample $server.Id;client=ProcessSample $child.Id}
                try {
                    $row.snapshot=Snapshot
                    # 采样只能发现捕获时刻的越界；配置与代码中的硬上限仍需测试保障。
                    foreach ($bound in @(@('lab.connections',$Connections),@('lab.outbound.slots',$Connections),
                        @('lab.outbound.bytes',134217728),@('lab.dispatch.bytes',4194304),@('lab.outbound.writes.inflight',$Connections),
                        @('lab.outbound.writers.active',8))) {
                        $value=Metric $row.snapshot $bound[0] -Required
                        if ($value -lt 0 -or $value -gt $bound[1]) { $sampling.Bounds=$false }
                    }
                } catch { $row.error=$_.Exception.Message; $sampling.Errors++ }
                $row | ConvertTo-Json -Compress -Depth 14 | Add-Content (Join-Path $output 'samples.jsonl') -Encoding utf8
                $sampling.Count++; $sampling.Next=$elapsed+1
            }
        }
        $status.processPassed=$true
    } catch { $failure=$_ }
    finally {
        Start-Sleep -Seconds 2
        try {
            $after=Snapshot
            $after | ConvertTo-Json -Depth 14 | Set-Content (Join-Path $output 'server-after.json') -Encoding utf8
            $status.cleanupReturnedToZero=$true
            foreach ($name in 'lab.connections','lab.rooms','lab.outbound.bytes','lab.outbound.messages','lab.outbound.slots',
                'lab.outbound.tasks','lab.outbound.writers.active','lab.outbound.writes.inflight','lab.dispatch.bytes',
                'lab.dispatch.messages','lab.dispatch.rooms','lab.dispatch.ready') {
                if ((Metric $after $name -Required) -ne 0) { $status.cleanupReturnedToZero=$false }
            }
            $delta=@{}
            foreach ($name in 'lab.messages.input','lab.messages.accepted','lab.messages.rejected','lab.connections.rejected',
                'lab.deliveries.expected','lab.deliveries.enqueued','lab.deliveries.submitted','lab.writes.failed',
                'lab.outbound.dropped','lab.outbound.closed','lab.dispatch.discarded','lab.deliveries.cancelled') {
                $delta[$name]=(Metric $after $name)-(Metric $before $name)
            }
            if (Test-Path (Join-Path $output 'client/summary.json')) {
                $report=Get-Content (Join-Path $output 'client/summary.json') -Raw | ConvertFrom-Json
                $status.clientPassed=[bool]$report.passed
                $status.serverInputCountersMatch=$delta['lab.messages.input'] -eq $report.sent -and
                    $delta['lab.messages.accepted'] -eq $report.accepted -and $delta['lab.messages.rejected'] -eq $report.rejected
                $status.admissionCountersMatch=$delta['lab.connections.rejected'] -eq $report.observedAdmissionRejections
                # 重连期成员快照不断变化：写回调成功不等于已读，不能硬凑全服submitted=客户端接收。
                $status.deliveryBoundsPassed=$report.requiredReceived -le $report.allReceived -and
                    $report.allReceived -le $delta['lab.deliveries.submitted'] -and
                    $delta['lab.deliveries.submitted'] -le $delta['lab.deliveries.expected'] -and
                    $delta['lab.deliveries.expected'] -le $report.accepted*$Connections
                @{server=$delta;note='计划关闭TCP可能引发写失败或丢弃；不删除固定在线观众分母，逐窗口真实接收才是通过依据。'} |
                    ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'counter-delta.json') -Encoding utf8
            }
        } catch { if (-not $failure) { $failure=$_ }; $status.collectionError=$_.Exception.Message }
        $status.collectorErrors=$sampling.Errors
        $status.sampledBoundsPassed=$sampling.Bounds -and $sampling.Count -gt 0
        $status.passed=$status.processPassed -and $status.clientPassed -and $status.cleanupReturnedToZero -and
            $status.serverInputCountersMatch -and $status.admissionCountersMatch -and $status.deliveryBoundsPassed -and
            $status.sampledBoundsPassed -and $status.collectorErrors -eq 0
        if ($failure) { $status.error=$failure.Exception.Message }
        $status | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'run-status.json') -Encoding utf8
    }
    if ($failure) { throw $failure }
    if (-not $status.passed) { throw "T6接收、计数、资源或清理不通过：$output" }
    foreach ($phase in $report.phases) { Write-Output "T6 $($phase.name): required=$($phase.requiredReceived)/$($phase.nominalRequiredDemand); all=$($phase.allReceived); p99=$($phase.latencyMs.p99)ms" }
    Write-Output "T6 passed: recovery ms=$($report.recovery.recoveryMs -join ', '); admission rejected=$($report.observedAdmissionRejections); $output"
} finally {
    if ($session) { $session.Dispose() }
    Pop-Location
}
