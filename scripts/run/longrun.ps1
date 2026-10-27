#requires -Version 7.0
<#
T3/T5 顺序执行入口：服务须提前启动；每轮单独目录，所有失败同样保存日志和前后指标。
T3 默认1000连接、10个慢读、900秒；T5 默认1000正常连接、1800秒，均预热60秒、输入20/s。
只编排与采样，不修改服务端配置；客户端工具采用原始 TCP recv 限速，复用有界子进程收尾。
#>
param(
    [Parameter(Mandatory)][ValidateSet('T3','T5')][string]$Scenario,
    [ValidateRange(1,2000)][int]$Connections=1000,
    [ValidateRange(1,3600)][int]$Seconds=1800,
    [ValidateRange(0,300)][int]$WarmupSeconds=60,
    [ValidateRange(1,40)][int]$Rate=20,
    [ValidateRange(0,100)][int]$SlowConnections=0,
    [ValidateRange(1,1048576)][int]$SlowBytesPerSecond=1024,
    [string]$HttpUrl='http://127.0.0.1:8083',
    [ValidateSet('direct','fair')][string]$DispatchMode='fair',
    [string]$OutputDirectory
)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'ProcessEvidence.psm1') -Force
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $root
try {
    if (-not $PSBoundParameters.ContainsKey('Seconds')) { $Seconds=if ($Scenario -eq 'T3') {900} else {1800} }
    if ($Scenario -eq 'T3' -and -not $PSBoundParameters.ContainsKey('SlowConnections')) { $SlowConnections=[Math]::Max(1,[int]($Connections/100)) }
    if (($Scenario -eq 'T5' -and $SlowConnections) -or $SlowConnections -ge $Connections) { throw '慢观众数与场景不符。' }
    $url=$HttpUrl.TrimEnd('/'); $uri=[Uri]$url
    if (-not $uri.IsLoopback) { throw '本入口只支持同机回环资源采集。' }
    if ((Invoke-RestMethod "$url/actuator/health" -TimeoutSec 5).status -ne 'UP') { throw '请先启动服务。' }
    if ((Invoke-RestMethod "$url/actuator/metrics/lab.outbound.mode.buffered" -TimeoutSec 3).measurements[0].value -ne 1) { throw '本轮要求 buffered 输出。' }
    $expectedMode=if ($DispatchMode -eq 'fair') {1} else {0}
    if ((Invoke-RestMethod "$url/actuator/metrics/lab.dispatch.mode.fair" -TimeoutSec 3).measurements[0].value -ne $expectedMode) { throw '调度模式不符。' }
    function Snapshot {
        $began=[DateTimeOffset]::UtcNow
        $index=Invoke-RestMethod "$url/actuator/metrics" -TimeoutSec 3
        $entries=@($index.names | Where-Object {$_ -like 'lab.*' -or $_ -in @('jvm.threads.live','jvm.gc.pause','process.cpu.usage')} |
            ForEach-Object {Invoke-RestMethod "$url/actuator/metrics/$_" -TimeoutSec 3})
        $entries+=Invoke-RestMethod "$url/actuator/metrics/jvm.memory.used?tag=area:heap" -TimeoutSec 3
        @{startedAt=$began.ToString('o');endedAt=[DateTimeOffset]::UtcNow.ToString('o');metrics=$entries}
    }
    function Metric($snapshot,[string]$name,[switch]$Required) {
        $entry=@($snapshot.metrics | Where-Object name -eq $name)
        if (-not $entry.Count) { if ($Required) {throw "缺少必需指标$name"}; return 0.0 }
        return [double]$entry[0].measurements[0].value
    }
    function ProcessSample([Diagnostics.Process]$item) {
        try {$item.Refresh(); @{pid=$item.Id;cpuSeconds=$item.TotalProcessorTime.TotalSeconds;workingSetBytes=$item.WorkingSet64;threads=$item.Threads.Count}}
        catch {$null}
    }
    $before=Snapshot
    if ((Metric $before 'lab.connections' -Required) -ne 0) { throw '运行前仍有其他连接。' }
    if (-not $OutputDirectory) {
        $now=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'China Standard Time')
        $OutputDirectory="docs/results/$($now.ToString('yyyy-MM-dd'))/$($Scenario.ToLower())-$($now.ToString('HHmmss'))"
    }
    if (Test-Path -LiteralPath $OutputDirectory) {throw '输出目录已存在，拒绝覆盖。'}
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $output=(Resolve-Path -LiteralPath $OutputDirectory).Path
    $before | ConvertTo-Json -Depth 14 | Set-Content (Join-Path $output 'server-before.json') -Encoding utf8
    $listener=Get-NetTCPConnection -State Listen -LocalPort $uri.Port | Select-Object -First 1
    $server=Get-Process -Id $listener.OwningProcess
    (Get-CimInstance Win32_Process -Filter "ProcessId=$($server.Id)").CommandLine | Set-Content (Join-Path $output 'server-command.txt') -Encoding utf8
    Copy-Item src/main/resources/application.yml (Join-Path $output 'application.yml')
    $manifest=@(Get-Item pom.xml)+@(Get-ChildItem src,scripts -Recurse -File | Where-Object FullName -notmatch '__pycache__')
    @($manifest | Sort-Object FullName | ForEach-Object {@{path=[IO.Path]::GetRelativePath($root,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}}) |
        ConvertTo-Json -Depth 4 | Set-Content (Join-Path $output 'source-sha256.json') -Encoding utf8
    Get-FileHash target/danmaku-lab-0.1.0-SNAPSHOT.jar | ConvertTo-Json | Set-Content (Join-Path $output 'jar-sha256.json') -Encoding utf8
    @{scenario=$Scenario;connections=$Connections;seconds=$Seconds;warmupSeconds=$WarmupSeconds;rate=$Rate;slowConnections=$SlowConnections;
      slowBytesPerSecond=$SlowBytesPerSecond;dispatchMode=$DispatchMode;outboundMode='buffered';sampleSeconds=5;inputBytes=256} |
        ConvertTo-Json | Set-Content (Join-Path $output 'config.json') -Encoding utf8
    @{capturedAt=[DateTimeOffset]::UtcNow.ToString('o');os=[Environment]::OSVersion.VersionString;
      cpu=(Get-CimInstance Win32_Processor).Name;logicalProcessors=[Environment]::ProcessorCount;
      python=(& python --version);java=((& java -version 2>&1)|Out-String).Trim();
      commit=(& git -c safe.directory=D:/coding/DanmakuLab rev-parse HEAD);
      status=((& git -c safe.directory=D:/coding/DanmakuLab status --short)|Out-String);
      note='Same-host loopback. CPU is cumulative seconds; sampled resource maxima are not absolute peaks. Slow clients stay in their original denominator.'} |
        ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'environment.json') -Encoding utf8
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=(Get-Command python).Source; $info.WorkingDirectory=$root
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.Environment['PYTHONUTF8']='1'
    $cliArguments=@('scripts/load/longrun.py','--scenario',$Scenario,'--url',(($url -replace '^http','ws')+'/ws'),
        '--connections',"$Connections",'--seconds',"$Seconds",'--warmup-seconds',"$WarmupSeconds",'--rate',"$Rate",
        '--slow-connections',"$SlowConnections",'--slow-bytes-per-second',"$SlowBytesPerSecond",'--output',(Join-Path $output 'client'))
    foreach ($argument in $cliArguments) {$info.ArgumentList.Add($argument)}
    @{executable=$info.FileName;arguments=$cliArguments} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'command.json') -Encoding utf8
    $sampling=[pscustomobject]@{Next=0.0;Progress=0.0;Errors=0}
    $failure=$null
    $status=[ordered]@{processPassed=$false;collectorErrors=0;cleanupReturnedToZero=$false;serverCountersMatch=$false;backpressureObserved=$null}
    try {
        Write-Output "$Scenario started: $Connections connections, slow=$SlowConnections, measured=$Seconds seconds; $output"
        $null=Invoke-ProcessWithEvidence -StartInfo $info -Prefix (Join-Path $output 'python') -TimeoutSeconds ($WarmupSeconds+$Seconds+100) -OnTick {
            param($child,$elapsed)
            if ($elapsed -ge $sampling.Progress) {Write-Host "$Scenario running: $([Math]::Floor($elapsed))s / warmup=$WarmupSeconds + measure=$Seconds + drain"; $sampling.Progress=$elapsed+10}
            if ($elapsed -ge $sampling.Next) {
                $row=@{elapsedSeconds=$elapsed;client=ProcessSample $child;server=ProcessSample $server}
                try {$row.snapshot=Snapshot} catch {$row.error=$_.Exception.Message; $sampling.Errors++}
                $row | ConvertTo-Json -Compress -Depth 14 | Add-Content (Join-Path $output 'samples.jsonl') -Encoding utf8
                $sampling.Next=$elapsed+5
            }
        }
        $status.processPassed=$true
    } catch {$failure=$_}
    finally {
        Start-Sleep -Seconds 2
        try {
            $after=Snapshot
            $after | ConvertTo-Json -Depth 14 | Set-Content (Join-Path $output 'server-after.json') -Encoding utf8
            $status.cleanupReturnedToZero=$true
            foreach ($name in 'lab.connections','lab.rooms','lab.outbound.bytes','lab.outbound.messages','lab.outbound.slots','lab.dispatch.bytes','lab.dispatch.messages','lab.dispatch.rooms','lab.dispatch.ready') {
                if ((Metric $after $name -Required) -ne 0) {$status.cleanupReturnedToZero=$false}
            }
            # 新异步输出还要等所有容器完成回调；旧制品没有该指标时仍可用同一入口保留历史对照。
            if ((Metric $after 'lab.outbound.writes.inflight') -ne 0) {$status.cleanupReturnedToZero=$false}
            $delta=@{}
            foreach ($name in 'lab.messages.input','lab.messages.accepted','lab.messages.rejected','lab.deliveries.expected','lab.deliveries.enqueued','lab.deliveries.submitted','lab.writes.failed','lab.outbound.dropped','lab.outbound.closed','lab.dispatch.discarded','lab.deliveries.cancelled') {
                $delta[$name]=(Metric $after $name)-(Metric $before $name)
            }
            $summary=Join-Path $output 'client/summary.json'
            if (Test-Path -LiteralPath $summary) {
                $report=Get-Content $summary -Raw | ConvertFrom-Json
                $rejected=0
                foreach ($property in $report.rejections.PSObject.Properties) {$rejected += [long]$property.Value}
                $status.serverCountersMatch=$delta['lab.messages.input'] -eq $report.submittedIncludingWarmup -and
                    $delta['lab.messages.accepted'] -eq $report.acceptedIncludingWarmup -and $delta['lab.messages.rejected'] -eq $rejected
                if ($Scenario -eq 'T5') {
                    $totalReceived=$report.normalReceived+$report.normalWarmupReceived
                    $status.serverCountersMatch=$status.serverCountersMatch -and $delta['lab.deliveries.expected'] -eq $totalReceived -and $delta['lab.deliveries.submitted'] -eq $totalReceived
                } else {
                    # 接收限速本身还不够：需见到服务端写失败或明确过载/过期降级，才能声称实际形成背压。
                    $status.backpressureObserved=$delta['lab.writes.failed'] -gt 0 -or $delta['lab.outbound.dropped'] -gt 0
                }
                @{client=$report;server=$delta} | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $output 'counter-delta.json') -Encoding utf8
            }
        } catch {if (-not $failure) {$failure=$_}; $status.collectionError=$_.Exception.Message}
        $status.collectorErrors=$sampling.Errors
        if ($failure) {$status.error=$failure.Exception.Message}
        $status | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'run-status.json') -Encoding utf8
    }
    if ($failure) {throw $failure}
    # 小规模工具短轮允许尚未压满 TCP 缓冲；正式900秒 T3 必须有服务端背压/降级证据。
    if (-not $status.cleanupReturnedToZero -or -not $status.serverCountersMatch -or $status.collectorErrors -or
        ($Scenario -eq 'T3' -and $Seconds -ge 900 -and -not $status.backpressureObserved)) {throw "长测的采集/回收/计数/背压证据不通过：$output"}
    Write-Output "$Scenario passed; normal=$($report.normalReceived)/$($report.normalExpected); latency=$($report.normalLatencyMs | ConvertTo-Json -Compress); $output"
} finally {Pop-Location}
