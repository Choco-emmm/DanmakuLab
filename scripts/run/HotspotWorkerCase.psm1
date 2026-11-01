#requires -Version 7.4
# 一轮不可变制品实验的生命周期：创建自有JVM、并行启动独立k6、采样、对账、有限清理。
# 它不决定加跑次数；批次计划在外层运行前固定。客户端失败也先保留证据，再返回失败行。
Import-Module (Join-Path $PSScriptRoot 'ProcessEvidence.psm1')

function Invoke-HotspotWorkerCase {
    param([string]$Root,[string]$Directory,[string]$Jar,[string]$JarHash,[string]$Java,
          [int]$Port=8085,[int]$Workers=5,[int]$PhaseSeconds=10,[int]$NormalRooms=10,
          [int]$NormalConnections=50,[int]$HotConnections=500,[string]$Dispatch='fair',
          [bool]$Diagnostic=$true,[int]$StallMs=0)
    $ErrorActionPreference='Stop'
    $null=New-Item -ItemType Directory -Path $Directory
    $directoryPath=(Resolve-Path -LiteralPath $Directory).Path
    $row=[ordered]@{directory=$directoryPath;workers=$Workers;phaseSeconds=$PhaseSeconds;dispatch=$Dispatch;
        diagnostic=$Diagnostic;stallMs=$StallMs;startedAt=[DateTimeOffset]::UtcNow.ToString('o');
        pid=$null;stopped=$false;passed=$false;evidenceComplete=$false;error=$null}
    $server=[Diagnostics.Process]::new();$capture=$null;$started=$false
    $http=[Net.Http.HttpClient]::new()
    $jobs=@();$after=$null;$before=$null
    $ids=[Collections.Concurrent.ConcurrentDictionary[string,int]]::new()
    $owned=[Collections.Concurrent.ConcurrentDictionary[string,Diagnostics.Process]]::new()
    $ready=[Collections.Concurrent.ConcurrentDictionary[string,long]]::new()
    $cancel=[Collections.Concurrent.ConcurrentDictionary[string,bool]]::new();$cancel['requested']=$false
    $collectorErrors=[Collections.Generic.List[object]]::new()
    $runError=$null
    $gauges=@{};foreach($name in 'lab.connections','lab.rooms','lab.outbound.bytes','lab.outbound.messages','lab.outbound.slots',
        'lab.outbound.tasks','lab.outbound.writers.active','lab.outbound.writes.inflight',
        'lab.dispatch.bytes','lab.dispatch.messages','lab.dispatch.rooms','lab.dispatch.ready'){$gauges[$name]=$null}

    function Get-Json([string]$Path,[DateTimeOffset]$Deadline=[DateTimeOffset]::UtcNow.AddSeconds(3)) {
        # HttpClient本身就是复用连接的会话。每次请求使用取消令牌，清理时还受总10秒截止限制。
        $remaining=($Deadline-[DateTimeOffset]::UtcNow).TotalSeconds
        if($remaining -le 0){throw 'HTTP采集截止已到'}
        $cts=[Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds([Math]::Min(3,$remaining)))
        try {($http.GetStringAsync("http://127.0.0.1:$Port$Path",$cts.Token).GetAwaiter().GetResult())|ConvertFrom-Json}
        finally{$cts.Dispose()}
    }
    function Snapshot {
        $time=[DateTimeOffset]::UtcNow
        $index=Get-Json '/actuator/metrics'
        $entries=@($index.names | Where-Object {$_ -like 'lab.*' -or $_ -in 'jvm.threads.live','jvm.gc.pause','process.cpu.usage'} |
            ForEach-Object {Get-Json "/actuator/metrics/$_"})
        $entries+=Get-Json '/actuator/metrics/jvm.memory.used?tag=area:heap'
        @{startedAt=$time.ToString('o');endedAt=[DateTimeOffset]::UtcNow.ToString('o');metrics=$entries}
    }
    function Sample-Process([Diagnostics.Process]$Process) {
        try{$Process.Refresh();@{pid=$Process.Id;cpuSeconds=$Process.TotalProcessorTime.TotalSeconds;workingSetBytes=$Process.WorkingSet64;threads=$Process.Threads.Count}}
        catch{$null} # 缺测保留null，不能把进程不可读当成资源归零。
    }
    try {
        if(@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq $Port).Count){throw '实验端口已占用'}
        if((Get-FileHash -LiteralPath $Jar).Hash -ne $JarHash){throw '制品哈希发生变化'}
        $info=[Diagnostics.ProcessStartInfo]::new()
        $info.FileName=$Java;$info.WorkingDirectory=$Root;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
        $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
        $arguments=@('-Xms128m','-Xmx256m','-jar',$Jar,"--server.port=$Port",'--lab.cluster.mode=local',
            # 将批准的8个提交线程显式写入命令，避免外部环境变量覆盖JAR默认值。
            '--lab.outbound.mode=buffered','--lab.outbound.workers=8',"--lab.dispatch.mode=$Dispatch")
        foreach($argument in $arguments){$info.ArgumentList.Add($argument)}
        $server.StartInfo=$info;$started=$server.Start();if(-not $started){throw 'JVM启动失败'}
        $row.pid=$server.Id;$capture=[DanmakuLab.ProcessTextCapture]::new($server)
        @{pid=$server.Id;executable=$Java;arguments=$arguments;jarSha256=$JarHash}|ConvertTo-Json -Depth 4|
            Set-Content (Join-Path $directoryPath 'server-launch.json') -Encoding utf8
        $watch=[Diagnostics.Stopwatch]::StartNew()
        while($true){
            if($server.HasExited){throw 'JVM提前退出'}
            try{if((Get-Json '/actuator/health').status -eq 'UP'){break}}catch{}
            if($watch.Elapsed.TotalSeconds -gt 30){throw 'JVM未在30秒内就绪'}
            Start-Sleep -Milliseconds 200
        }
        $before=Snapshot
        $before|ConvertTo-Json -Depth 15|Set-Content (Join-Path $directoryPath 'server-before.json') -Encoding utf8
        foreach($name in $gauges.Keys){
            $metric=@($before.metrics|Where-Object name -eq $name)
            if($metric.Count -ne 1 -or $metric[0].measurements[0].value -ne 0){throw "实验前$name未知或非零"}
        }
        # 先保存环境/源码，再确定30秒共同起点，避免哈希计算侵占加入预算。
        @{capturedAt=[DateTimeOffset]::UtcNow.ToString('o');java=((& $Java -version 2>&1)|Out-String).Trim();
          k6=((& k6 version)|Out-String).Trim();cpu=(Get-CimInstance Win32_Processor).Name;logicalProcessors=[Environment]::ProcessorCount;
          os=[Environment]::OSVersion.VersionString;sourceCommit=(& git -c safe.directory=D:/coding/DanmakuLab rev-parse HEAD);
          note='Same-host loopback; Java and all load clients share resources; sequential metric snapshots.'}|
            ConvertTo-Json|Set-Content (Join-Path $directoryPath 'environment.json') -Encoding utf8
        $files=@(Get-Item (Join-Path $Root 'pom.xml'))+@(Get-ChildItem (Join-Path $Root 'src'),(Join-Path $Root 'scripts') -Recurse -File|Where-Object FullName -notmatch '__pycache__')
        @($files|Sort-Object FullName|ForEach-Object {@{path=[IO.Path]::GetRelativePath($Root,$_.FullName);sha256=(Get-FileHash $_.FullName).Hash}})|
            ConvertTo-Json|Set-Content (Join-Path $directoryPath 'source-sha256.json') -Encoding utf8
        @{Hash=$JarHash;Path=$Jar}|ConvertTo-Json|Set-Content (Join-Path $directoryPath 'jar-sha256.json') -Encoding utf8
        $key=[Guid]::NewGuid().ToString('N');$start=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()+30000L
        $cfg=@{schemaVersion=1;runKey=$key;startAtMs=$start;hotWorkers=$Workers;hotConnections=$HotConnections;
            normalRooms=$NormalRooms;normalConnections=$NormalConnections;phaseSeconds=$PhaseSeconds;dispatch=$Dispatch;
            diagnostic=$Diagnostic;stallMs=$StallMs;sampleSeconds=$(if($Diagnostic){1}else{5});drainSeconds=5;
            formalEligible=(-not $Diagnostic);requestBytes=256;jarSha256=$JarHash}
        $cfg|ConvertTo-Json|Set-Content (Join-Path $directoryPath 'config.json') -Encoding utf8
        $roles=@('normal')+@(0..($Workers-1)|ForEach-Object {"hot-$_"})
        @{roles=$roles;startAtMs=$start}|ConvertTo-Json|Set-Content (Join-Path $directoryPath 'schedule.json') -Encoding utf8
        $k6=(Get-Command k6).Source
        $timeout=30+4*$PhaseSeconds+40
        foreach($role in $roles){
            $folder=Join-Path $directoryPath $role;$null=New-Item -ItemType Directory -Path $folder
            $isNormal=$role -eq 'normal';$w=if($isNormal){0}else{[int]$role.Substring(4)}
            $script=if($isNormal){'scripts/load/hotspot.js'}else{'scripts/load/hotspot-workers.js'}
            $envs=@{ROLE=$(if($isNormal){'normal'}else{'hot-worker'});RUN_KEY=$key;HOT_WORKERS=$Workers;HOT_WORKER_INDEX=$w;
                HOT_CONNECTIONS=$HotConnections;NORMAL_ROOMS=$NormalRooms;NORMAL_CONNECTIONS=$NormalConnections;
                PHASE_SECONDS=$PhaseSeconds;DRAIN_SECONDS=5;START_AT_MS=$start;LATENCY_SAMPLE_EVERY=10;
                WS_URL="ws://127.0.0.1:$Port/ws";DIAGNOSTIC=$Diagnostic.ToString().ToLowerInvariant();
                DIAGNOSTIC_STALL_MS=$(if(-not $isNormal -and $w -eq 0){$StallMs}else{0});SUMMARY_PATH=(Join-Path $folder 'k6-summary.json')}
            $argsList=@('run','--quiet','--no-usage-report','--address','127.0.0.1:0')
            foreach($name in $envs.Keys){$argsList+=@('-e',"$name=$($envs[$name])")};$argsList+=$script
            @{executable=$k6;arguments=$argsList}|ConvertTo-Json -Depth 4|Set-Content (Join-Path $folder 'command.json') -Encoding utf8
            # ThreadJob仅看守独立OS进程；字典安全地传递PID、就绪标记和取消请求。
            # 默认ThreadJob只同时运行5项；本轮最多6个k6，必须明确容纳全部工作进程。
            $jobs+=Start-ThreadJob -ThrottleLimit 6 -ArgumentList $Root,$k6,$argsList,$folder,$role,$timeout,$ids,$owned,$ready,$cancel -ScriptBlock {
                param($root,$exe,$arguments,$folder,$role,$timeout,$ids,$owned,$ready,$cancel)
                $ErrorActionPreference='Stop';Import-Module (Join-Path $root 'scripts/run/ProcessEvidence.psm1')
                $child=[Diagnostics.ProcessStartInfo]::new()
                $child.FileName=$exe;$child.WorkingDirectory=$root;$child.UseShellExecute=$false;$child.CreateNoWindow=$true
                $child.RedirectStandardOutput=$true;$child.RedirectStandardError=$true;$child.Environment['K6_WEB_DASHBOARD']='false'
                foreach($a in $arguments){$child.ArgumentList.Add([string]$a)}
                Invoke-ProcessWithEvidence -StartInfo $child -Prefix (Join-Path $folder 'k6') -TimeoutSeconds $timeout -OnTick {
                    param($process,$elapsed,$capture)
                    $ids[$role]=$process.Id;$owned[$role]=$process
                    if($cancel['requested']){throw '父采集流程取消此轮；保留原始证据'}
                    if(-not $ready.ContainsKey($role)){
                        # 仅在加入阶段读日志；就绪后不反复复制包含大型位图的终态输出。
                        $log=$capture.StandardError
                        if(($role -eq 'normal' -and $log.Contains('T4 normal: all ')) -or
                           ($role -ne 'normal' -and $log -match 'HW_EVENT.{1,5}type.{1,4}:.{1,3}ready')){
                            $ready[$role]=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                        }
                    }
                }
            }
        }
        Write-Host "HW started: workers=$Workers mode=$Dispatch phase=${PhaseSeconds}s; $directoryPath"
        $clock=[Diagnostics.Stopwatch]::StartNew();$next=0.0;$progress=0.0
        try {
            while(@($jobs|Where-Object State -in 'Running','NotStarted').Count){
                $elapsed=$clock.Elapsed.TotalSeconds;$now=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                if($server.HasExited){throw '测量期间JVM退出'}
                if($now -ge $start-1000 -and $ready.Count -ne $roles.Count){throw '未能在共同起点前全部就绪，整轮无效'}
                if($elapsed -gt $timeout+10){throw '客户端任务超过总截止'}
                if($elapsed -ge $progress){Write-Host "HW running: $([int]$elapsed)s; ready=$($ready.Count)/$($roles.Count)";$progress=$elapsed+30}
                if($elapsed -ge $next){
                    $clients=@{}
                    foreach($r in $roles){if($ids.ContainsKey($r)){$p=Get-Process -Id $ids[$r] -ErrorAction SilentlyContinue;$clients[$r]=if($p){Sample-Process $p}else{$null}}}
                    $sample=@{elapsedSeconds=$elapsed;atMs=$now;server=Sample-Process $server;clients=$clients}
                    try{$sample.snapshot=Snapshot}catch{$sample.error=$_.Exception.Message;$collectorErrors.Add(@{atMs=$now;error=$sample.error})}
                    $sample|ConvertTo-Json -Depth 15 -Compress|Add-Content (Join-Path $directoryPath 'samples.jsonl') -Encoding utf8
                    $next=$elapsed+$cfg.sampleSeconds
                }
                Start-Sleep -Milliseconds 100
            }
            foreach($job in $jobs){try{$null=Receive-Job $job -ErrorAction Stop}catch{if(-not $runError){$runError=$_}}}
        } catch{$runError=$_}
    } catch{$runError=$_}
    finally {
        $cancel['requested']=$true
        if($jobs.Count){
            $null=Wait-Job -Job $jobs -Timeout 10
            foreach($r in @($owned.Keys)){
                # 持有本轮Start返回的对象，不根据可能复用的PID去杀用户的新进程。
                try{if(-not $owned[$r].HasExited){$owned[$r].Kill($true);$null=$owned[$r].WaitForExit(5000)}}catch{}
            }
            foreach($job in $jobs){if($job.State -in 'Running','NotStarted'){Stop-Job $job}}
            Remove-Job $jobs -Force
        }
        if($started -and -not $server.HasExited){
            $deadline=[DateTimeOffset]::UtcNow.AddSeconds(10)
            try {
                do {
                    foreach($name in @($gauges.Keys)){
                        $g=Get-Json "/actuator/metrics/$name" $deadline
                        if($null -eq $g.measurements[0].value){throw "缺少Gauge $name"}
                        $gauges[$name]=$g.measurements[0].value
                    }
                    if(@($gauges.Values|Where-Object {$_ -ne 0}).Count -eq 0){break}
                    Start-Sleep -Milliseconds 100
                }while([DateTimeOffset]::UtcNow -lt $deadline)
            } catch{$collectorErrors.Add(@{stage='cleanup';error=$_.Exception.Message})}
            try{$after=Snapshot;$after|ConvertTo-Json -Depth 15|Set-Content (Join-Path $directoryPath 'server-after.json') -Encoding utf8}
            catch{$collectorErrors.Add(@{stage='final-snapshot';error=$_.Exception.Message})}
        }
        $gauges|ConvertTo-Json|Set-Content (Join-Path $directoryPath 'cleanup-gauges.json') -Encoding utf8
        ConvertTo-Json -InputObject @($collectorErrors.ToArray()) -Depth 5|Set-Content (Join-Path $directoryPath 'collector-errors.json') -Encoding utf8
        @{collectorErrors=$collectorErrors.Count;allReadyBeforeStart=($ready.Count -eq $roles.Count -and @($ready.Values|Where-Object {$_ -ge $start}).Count -eq 0);
          processIds=$ids;readiness=$ready;error=$(if($runError){$runError.Exception.Message}else{$null})}|
            ConvertTo-Json -Depth 5|Set-Content (Join-Path $directoryPath 'run-status.json') -Encoding utf8
        try {
            & python -X utf8 (Join-Path $Root 'scripts/tests/hotspot_workers_audit.py') $directoryPath --evidence-only *> (Join-Path $directoryPath 'audit-output.txt')
            if($LASTEXITCODE -eq 0){$a=Get-Content (Join-Path $directoryPath 'audit.json') -Raw|ConvertFrom-Json;$row.passed=$a.passed;$row.evidenceComplete=$a.evidenceComplete}
        } catch{if(-not $runError){$runError=$_}}
        if($runError){$row.error=$runError.Exception.Message;$row.passed=$false}
        if($started){if(-not $server.HasExited){$server.Kill($true)};$row.stopped=$server.WaitForExit(5000)}
        if($capture){$null=$capture.Completion.Wait(5000);$capture.StandardOutput|Set-Content (Join-Path $directoryPath 'server-stdout.txt') -Encoding utf8;
            $capture.StandardError|Set-Content (Join-Path $directoryPath 'server-stderr.txt') -Encoding utf8}
        $http.Dispose();$server.Dispose()
        $row.endedAt=[DateTimeOffset]::UtcNow.ToString('o')
        $row|ConvertTo-Json -Depth 5|Set-Content (Join-Path $directoryPath 'case-status.json') -Encoding utf8
    }
    [pscustomobject]$row
}
Export-ModuleMember -Function Invoke-HotspotWorkerCase
