#requires -Version 7.4
<#
T2证据入口：1000观众固定在同一房间，四个独立客户端进程分担接收，逐档提高输入。
每档默认预热15秒、测量180秒；房间额度由独立服务显式设为200/s，避免100/s先被默认50/s挡住。
本入口核对启动命令与模式，不改服务器；指标HTTP整轮复用连接，失败不静默抹掉。
#>
param(
    [ValidateRange(8,2000)][int]$Connections=1000,
    [ValidateRange(1,4)][int]$Workers=4,
    [ValidateRange(1,180)][int]$PhaseSeconds=180,
    [ValidateRange(0,60)][int]$WarmupSeconds=15,
    [ValidateRange(1,2000)][int]$LatencySampleEvery=10,
    [string]$Rates='5,20,50,100',
    [ValidateRange(100,1000)][int]$RoomLimit=200,
    [ValidateSet('direct','fair')][string]$DispatchMode='fair',
    [string]$HttpUrl='http://127.0.0.1:8085',
    [string]$OutputDirectory
)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'ProcessEvidence.psm1') -Force
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$session=$null
Push-Location $root
try {
    $url=$HttpUrl.TrimEnd('/'); $uri=[Uri]$url
    if (-not $uri.IsLoopback) { throw '当前采集入口只支持同机回环。' }
    $session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
    function Read-Endpoint([string]$Path) {
        Invoke-RestMethod "$url$Path" -TimeoutSec 3 -WebSession $session
    }
    function Metric($Snapshot,[string]$Name,[switch]$Required) {
        $entry=@($Snapshot.metrics | Where-Object name -eq $Name)
        if (-not $entry.Count) {
            if ($Required) { throw "缺少必需Gauge：$Name" }
            return 0.0 # 从未发生的Counter可能尚未注册；Gauge不允许走此分支。
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
        } catch { $null } # 恰好退出不冒充资源归零，最终以进程状态和服务端快照核对。
    }
    if ((Read-Endpoint '/actuator/health').status -ne 'UP') { throw '服务未就绪。' }
    $listener=Get-NetTCPConnection -State Listen -LocalPort $uri.Port | Select-Object -First 1
    $server=Get-Process -Id $listener.OwningProcess
    $serverCommand=(Get-CimInstance Win32_Process -Filter "ProcessId=$($server.Id)").CommandLine
    # CLI属性优先于yml/环境变量；要求恰好一个规范写法，避免把文件默认50当作实验实际额度。
    $switches=[regex]::Matches($serverCommand,'(?:^|\s)--lab\.room-messages-per-second=(\d+)(?=\s|$)')
    if ($switches.Count -ne 1 -or [int]$switches[0].Groups[1].Value -ne $RoomLimit) {
        throw "请单独启动服务并显式指定 --lab.room-messages-per-second=$RoomLimit；本入口不会替换服务或修改限流。"
    }
    $before=Snapshot
    if ((Metric $before 'lab.connections' -Required) -ne 0) { throw '实验前仍有连接，请使用空闲独立服务。' }
    if ((Metric $before 'lab.outbound.mode.buffered' -Required) -ne 1 -or
        (Metric $before 'lab.dispatch.mode.fair' -Required) -ne $(if ($DispatchMode -eq 'fair') {1} else {0})) { throw '模式不匹配。' }
    # 当前T2要求异步写路径；缺少Gauge不能按旧版本兼容成零。
    $null=Metric $before 'lab.outbound.writes.inflight' -Required
    if (-not $OutputDirectory) {
        $now=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'China Standard Time')
        $OutputDirectory="docs/results/$($now.ToString('yyyy-MM-dd'))/t2-$($now.ToString('HHmmss'))"
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
    @{scenario='T2';connections=$Connections;workers=$Workers;phaseSeconds=$PhaseSeconds;warmupSeconds=$WarmupSeconds;
      rates=$Rates;roomLimit=$RoomLimit;dispatchMode=$DispatchMode;outboundMode='buffered';latencySampleEvery=$LatencySampleEvery;
      resourceSampleSeconds=5;inputJsonBytes=256} | ConvertTo-Json | Set-Content (Join-Path $output 'config.json') -Encoding utf8
    @{capturedAt=[DateTimeOffset]::UtcNow.ToString('o');os=[Environment]::OSVersion.VersionString;
      cpu=(Get-CimInstance Win32_Processor).Name;logicalProcessors=[Environment]::ProcessorCount;
      python=(& python --version);powershell=$PSVersionTable.PSVersion.ToString();java=((& java -version 2>&1)|Out-String).Trim();
      commit=(& git -c safe.directory=D:/coding/DanmakuLab rev-parse HEAD);
      status=((& git -c safe.directory=D:/coding/DanmakuLab status --short)|Out-String);
      note='Same-host loopback; all processes share CPU/RAM. Sampled maxima are not absolute peaks. HTTP metrics use one shared session.'} |
        ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'environment.json') -Encoding utf8
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=(Get-Command python).Source; $info.WorkingDirectory=$root
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.Environment['PYTHONUTF8']='1'
    $arguments=@('scripts/load/throughput.py','--url',(($url -replace '^http','ws')+'/ws'),
        '--connections',"$Connections",'--workers',"$Workers",'--seconds',"$PhaseSeconds",'--warmup-seconds',"$WarmupSeconds",
        '--rates',$Rates,'--latency-sample-every',"$LatencySampleEvery",'--output',(Join-Path $output 'client'))
    foreach ($argument in $arguments) { $info.ArgumentList.Add($argument) }
    @{executable=$info.FileName;arguments=$arguments} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'command.json') -Encoding utf8
    $sampling=[pscustomobject]@{Next=0.0;Progress=0.0;Errors=0}
    $status=[ordered]@{processPassed=$false;collectorErrors=0;cleanupReturnedToZero=$false;serverInputCountersMatch=$false;
        deliveryCountersMatch=$false;toolValid=$false;allPhasesSloPassed=$false;formalParametersSatisfied=($Connections -eq 1000 -and $PhaseSeconds -eq 180 -and $Rates -eq '5,20,50,100')}
    $failure=$null
    try {
        Write-Output "T2 started: $Connections connections; rates=$Rates; room limit=$RoomLimit/s; evidence: $output"
        $null=Invoke-ProcessWithEvidence -StartInfo $info -Prefix (Join-Path $output 'python') -TimeoutSeconds (4*($PhaseSeconds+$WarmupSeconds)+115) -OnTick {
            param($child,$elapsed)
            if ($elapsed -ge $sampling.Progress) { Write-Host "T2 running: $([Math]::Floor($elapsed))s; four measured windows=$($PhaseSeconds*4)s plus warmup/drain"; $sampling.Progress=$elapsed+10 }
            if ($elapsed -ge $sampling.Next) {
                $row=@{elapsedSeconds=$elapsed;server=ProcessSample $server.Id;parent=ProcessSample $child.Id;workers=@{}}
                $ids=Join-Path $output 'client/processes.json'
                if (Test-Path -LiteralPath $ids) {
                    $workerIds=(Get-Content $ids -Raw | ConvertFrom-Json).workers
                    for ($index=0;$index -lt $workerIds.Count;$index++) { $row.workers["$index"]=ProcessSample $workerIds[$index] }
                }
                try { $row.snapshot=Snapshot } catch { $row.error=$_.Exception.Message; $sampling.Errors++ }
                $row | ConvertTo-Json -Compress -Depth 14 | Add-Content (Join-Path $output 'samples.jsonl') -Encoding utf8
                $sampling.Next=$elapsed+5
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
            foreach ($name in 'lab.messages.input','lab.messages.accepted','lab.messages.rejected','lab.deliveries.expected',
                'lab.deliveries.enqueued','lab.deliveries.submitted','lab.writes.failed','lab.outbound.dropped','lab.outbound.closed',
                'lab.dispatch.discarded','lab.deliveries.cancelled') { $delta[$name]=(Metric $after $name)-(Metric $before $name) }
            if (Test-Path (Join-Path $output 'client/summary.json')) {
                $report=Get-Content (Join-Path $output 'client/summary.json') -Raw | ConvertFrom-Json
                $status.toolValid=[bool]$report.toolValid
                $status.serverInputCountersMatch=$delta['lab.messages.input'] -eq $report.sentIncludingWarmup -and
                    $delta['lab.messages.accepted'] -eq $report.acceptedIncludingWarmup -and $delta['lab.messages.rejected'] -eq $report.rejectedIncludingWarmup
                $status.deliveryCountersMatch=$delta['lab.deliveries.expected'] -eq $report.expectedAcceptedIncludingWarmup -and
                    $delta['lab.deliveries.submitted'] -eq $report.receivedIncludingWarmup
                $status.allPhasesSloPassed=$report.allPhasesSloPassed -and $status.deliveryCountersMatch -and
                    $delta['lab.outbound.dropped'] -eq 0 -and $delta['lab.writes.failed'] -eq 0 -and $delta['lab.dispatch.discarded'] -eq 0
                @{client=$report;server=$delta} | ConvertTo-Json -Depth 14 | Set-Content (Join-Path $output 'counter-delta.json') -Encoding utf8
            }
        } catch { if (-not $failure) { $failure=$_ }; $status.collectionError=$_.Exception.Message }
        $status.collectorErrors=$sampling.Errors
        if ($failure) { $status.error=$failure.Exception.Message }
        $status | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'run-status.json') -Encoding utf8
    }
    if ($failure) { throw $failure }
    if (-not $status.cleanupReturnedToZero -or -not $status.serverInputCountersMatch -or $status.collectorErrors -or -not $status.toolValid) {
        throw "T2工具有效性、采集、计数或清理不通过：$output"
    }
    # 高速档性能未达标也保留有效实验，不能改小输入、减少观众或只打印一个笼统Passed。
    foreach ($phase in $report.phases) {
        Write-Output "T2 $($phase.rate)/s: received=$($phase.received)/$($phase.acceptedDeliveryDemand); rejected=$($phase.rejected); p99=$($phase.latencyMs.p99)ms; candidateSlo=$($phase.candidateSloPassed)"
    }
    Write-Output "T2 completed: toolValid=$($status.toolValid); allPhasesSloPassed=$($status.allPhasesSloPassed); $output"
} finally {
    if ($session) { $session.Dispose() }
    Pop-Location
}
