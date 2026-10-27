#requires -Version 7.4
<#
Redis双节点验收入口。只连接8087/8088，故障仅针对明确匹配本实验JAR的节点与专用容器。
HTTP整轮复用会话；正常轮严格核对两节点发布/实际接收计数，故障轮不伪造已退出节点的最终快照。
#>
param(
    [ValidateRange(4,1000)][int]$Connections=100,
    [ValidateRange(1,300)][int]$Seconds=60,
    [ValidateSet('steady','redis-outage','redis-timeout','node-exit','slow')][string]$Scenario='steady',
    [int]$SampleEvery=10,
    [string]$OutputDirectory
)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'ProcessEvidence.psm1') -Force
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
Push-Location $root
try {
    # 在创建本轮结果目录之前记录源码状态；否则新生成的证据文件本身会让干净检出也变成dirty。
    $sourceCommit=(& git -c safe.directory=D:/coding/DanmakuLab rev-parse HEAD)
    if($LASTEXITCODE -ne 0){throw '无法记录源码提交'}
    $sourceStatus=@(& git -c safe.directory=D:/coding/DanmakuLab status --porcelain)
    if($LASTEXITCODE -ne 0){throw '无法记录源码工作区状态'}
    function Endpoint([int]$Port,[string]$Path) { Invoke-RestMethod "http://127.0.0.1:$Port$Path" -WebSession $session -TimeoutSec 3 }
    function Snapshot([int]$Port) {
        $names=(Endpoint $Port '/actuator/metrics').names
        $items=@($names | Where-Object {$_ -like 'lab.*' -or $_ -in 'jvm.threads.live','process.cpu.usage'} |
            ForEach-Object { Endpoint $Port "/actuator/metrics/$_" })
        $items+=Endpoint $Port '/actuator/metrics/jvm.memory.used?tag=area:heap'
        @{at=[DateTimeOffset]::UtcNow.ToString('o');metrics=$items}
    }
    function Metric($Snapshot,[string]$Name,[switch]$Required) {
        $entry=@($Snapshot.metrics | Where-Object name -eq $Name)
        if(-not $entry.Count) { if($Required){throw "缺少Gauge：$Name"}; return 0.0 }
        [double]$entry[0].measurements[0].value
    }
    function ProcessSample([int]$ProcessId) {
        try { $p=Get-Process -Id $ProcessId -ErrorAction Stop; @{pid=$p.Id;cpuSeconds=$p.TotalProcessorTime.TotalSeconds;workingSetBytes=$p.WorkingSet64;threads=$p.Threads.Count} }
        catch { $null }
    }
    $before=@{}; $commands=@{}; $ids=@{}
    foreach($port in 8087,8088) {
        if((Endpoint $port '/actuator/health').status -ne 'UP'){throw "节点$port未启动"}
        $ids["$port"]=(Get-NetTCPConnection -State Listen -LocalPort $port | Select-Object -First 1).OwningProcess
        $commands["$port"]=(Get-CimInstance Win32_Process -Filter "ProcessId=$($ids["$port"])").CommandLine
        if($commands["$port"] -notlike "*danmaku-lab-redis.jar*--server.port=$port*--lab.cluster.mode=redis*"){throw '服务启动命令不匹配'}
        $before["$port"]=Snapshot $port
        if((Metric $before["$port"] 'lab.connections' -Required) -ne 0){throw '实验前必须无观众连接'}
        if((Metric $before["$port"] 'lab.cluster.ready' -Required) -ne 1){throw '订阅未就绪'}
    }
    # 默认按本机当天日期归档，避免跨天运行仍把新实验混进实现当天的历史目录。
    if(-not $OutputDirectory){$OutputDirectory="docs/results/$((Get-Date).ToString('yyyy-MM-dd'))/redis-$Scenario-$((Get-Date).ToString('HHmmss'))"}
    if(Test-Path $OutputDirectory){throw '结果目录已存在，拒绝覆盖'}
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $output=(Resolve-Path $OutputDirectory).Path
    $before | ConvertTo-Json -Depth 14 | Set-Content "$output/server-before.json" -Encoding utf8
    $commands | ConvertTo-Json | Set-Content "$output/server-commands.json" -Encoding utf8
    @{connections=$Connections;seconds=$Seconds;scenario=$Scenario;rate=20;sampleEvery=$SampleEvery;
        normalPhysical=$Connections+2;nodeIds=$ids;resourceSampleSeconds=2;note='另有两个隔离探针；slow额外一个限速观众；同机回环'} |
        ConvertTo-Json -Depth 5 | Set-Content "$output/config.json" -Encoding utf8
    $files=@(Get-Item pom.xml)+@(Get-ChildItem src,scripts,deploy -Recurse -File | Where-Object FullName -notmatch '__pycache__')
    @($files | ForEach-Object {@{path=[IO.Path]::GetRelativePath($root,$_.FullName);sha256=(Get-FileHash $_.FullName).Hash}}) |
        ConvertTo-Json -Depth 4 | Set-Content "$output/source-sha256.json" -Encoding utf8
    Get-FileHash target/danmaku-lab-redis.jar | ConvertTo-Json | Set-Content "$output/jar-sha256.json" -Encoding utf8
    @{cpu=(Get-CimInstance Win32_Processor).Name;logicalProcessors=[Environment]::ProcessorCount;
        powershell=$PSVersionTable.PSVersion.ToString();python=(& python --version);java=((& java -version 2>&1)|Out-String).Trim();
        docker=(& docker version --format '{{.Server.Version}}');redisImage=(& docker inspect danmakulab-redis-lab --format '{{.Image}}');
        commit=$sourceCommit;uncommitted=($sourceStatus.Count -gt 0);gitStatus=$sourceStatus} |
        ConvertTo-Json | Set-Content "$output/environment.json" -Encoding utf8
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=(Get-Command python).Source; $info.WorkingDirectory=$root
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.Environment['PYTHONUTF8']='1'
    $arguments=@('scripts/load/cluster.py','--connections',"$Connections",'--seconds',"$Seconds",'--scenario',$Scenario,
        '--sample-every',"$SampleEvery",'--node-a-pid',"$($ids['8087'])",'--output',"$output/client")
    foreach($item in $arguments){$info.ArgumentList.Add($item)}
    @{executable=$info.FileName;arguments=$arguments} | ConvertTo-Json | Set-Content "$output/command.json" -Encoding utf8
    $state=[pscustomobject]@{Next=0.0;Progress=0.0;Errors=0}
    $failure=$null; $after=@{}; $delta=@{}
    try {
        Write-Output "Redis $Scenario started: $Connections normal viewers + probes; evidence=$output"
        $null=Invoke-ProcessWithEvidence -StartInfo $info -Prefix "$output/python" -TimeoutSeconds ($Seconds+120) -OnTick {
            param($child,$elapsed)
            if($elapsed -ge $state.Progress){Write-Host "Redis $Scenario running: $([Math]::Floor($elapsed))s"; $state.Progress=$elapsed+10}
            if($elapsed -ge $state.Next){
                $row=@{elapsedSeconds=$elapsed;client=ProcessSample $child.Id;nodes=@{}}
                foreach($port in 8087,8088){
                    try { $row.nodes["$port"]=@{process=ProcessSample $ids["$port"];snapshot=Snapshot $port} }
                    catch {
                        if($Scenario -eq 'node-exit' -and $port -eq 8087 -and -not (ProcessSample $ids['8087'])) {
                            $row.nodes['8087']=@{expectedStopped=$true}
                        } else { $row.nodes["$port"]=@{error=$_.Exception.Message}; $state.Errors++ }
                    }
                }
                # Docker统计包含Redis容器CPU/内存；停机轮允许明确的容器离线，不冒充运行时零占用。
                $row.redisStats=(& docker stats danmakulab-redis-lab --no-stream --format '{{json .}}' 2>&1 | Out-String).Trim()
                $row | ConvertTo-Json -Depth 14 -Compress | Add-Content "$output/samples.jsonl" -Encoding utf8
                $state.Next=$elapsed+2
            }
        }
    } catch {$failure=$_}
    finally {
        Start-Sleep -Seconds 2
        $clean=$true
        foreach($port in 8087,8088){
            if($Scenario -eq 'node-exit' -and $port -eq 8087 -and -not (ProcessSample $ids['8087'])){
                $after['8087']=@{expectedStopped=$true;note='进程已退出，不能取得最终计数或声称Gauge归零'}; continue
            }
            try {
                $after["$port"]=Snapshot $port
                foreach($name in 'lab.connections','lab.rooms','lab.outbound.bytes','lab.outbound.messages','lab.outbound.slots',
                    'lab.outbound.tasks','lab.outbound.writers.active','lab.outbound.writes.inflight','lab.dispatch.bytes',
                    'lab.dispatch.messages','lab.dispatch.rooms','lab.dispatch.ready','lab.cluster.publish.messages',
                    'lab.cluster.publish.bytes','lab.cluster.receive.messages','lab.cluster.receive.bytes'){
                    if((Metric $after["$port"] $name -Required) -ne 0){$clean=$false}
                }
                $delta["$port"]=@{}
                foreach($name in 'lab.messages.input','lab.messages.accepted','lab.messages.rejected','lab.messages.unknown',
                    'lab.cluster.published','lab.cluster.received','lab.cluster.dropped','lab.cluster.local.enqueued',
                    'lab.deliveries.expected','lab.deliveries.submitted','lab.outbound.dropped','lab.writes.failed'){
                    $delta["$port"][$name]=(Metric $after["$port"] $name)-(Metric $before["$port"] $name)
                }
            } catch {$clean=$false; if(-not $failure){$failure=$_}}
        }
        $after | ConvertTo-Json -Depth 14 | Set-Content "$output/server-after.json" -Encoding utf8
        $delta | ConvertTo-Json -Depth 6 | Set-Content "$output/counter-delta.json" -Encoding utf8
        $report=$null
        if(Test-Path "$output/client/summary.json"){$report=Get-Content "$output/client/summary.json" -Raw | ConvertFrom-Json}
        $counters=$true
        if($report -and $Scenario -in 'steady','redis-outage','redis-timeout'){
            foreach($pair in @(@('lab.messages.input','sent'),@('lab.messages.accepted','accepted'),@('lab.messages.rejected','rejected'),@('lab.messages.unknown','unknown'))){
                $sum=($delta.Values | ForEach-Object {$_[$pair[0]]} | Measure-Object -Sum).Sum
                if($sum -ne $report.($pair[1])){$counters=$false}
            }
            $submitted=($delta.Values | ForEach-Object {$_['lab.deliveries.submitted']} | Measure-Object -Sum).Sum
            if($submitted -ne $report.received){$counters=$false}
        }
        $passed= -not $failure -and $report -and $report.passed -and $clean -and $state.Errors -eq 0 -and $counters
        @{passed=[bool]$passed;collectorErrors=$state.Errors;cleanupPassed=$clean;countersMatch=$counters;
            error=$(if($failure){$failure.Exception.Message}else{$null});nodeExitCountersPartial=($Scenario -eq 'node-exit')} |
            ConvertTo-Json | Set-Content "$output/run-status.json" -Encoding utf8
    }
    if($failure){throw $failure}
    if(-not $passed){throw "Redis实验未通过：$output"}
    Write-Output "Redis passed: received=$($report.received), accepted=$($report.accepted), rejected=$($report.rejected), unknown=$($report.unknown); $output"
} finally {$session.Dispose(); Pop-Location}
