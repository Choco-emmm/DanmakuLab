#requires -Version 7.4
<#
固定候选的T3/T5重复入口：默认只做短验收；RunFormal在短验收通过后执行T3三轮、T5三轮。
每次创建自己的JVM并顺序运行，只停止持有的自建Process对象。正式失败即停，不重试凑次数。
完整结果留在独立忽略目录，公开结果目录保留摘要、命令与哈希索引；不改Java或业务配置。
#>
param([switch]$RunFormal,[string]$OutputDirectory,[string]$JarPath='target/danmaku-lab-release.jar',
      [ValidateRange(1024,65535)][int]$Port=8085)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Import-Module (Join-Path $PSScriptRoot 'ProcessEvidence.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'ExperimentAwake.psm1') -Force
Push-Location $root
$output=$null;$awake=$null;$failure=$null
$rows=[Collections.Generic.List[object]]::new()
$state=[ordered]@{schemaVersion=1;startedAt=[DateTimeOffset]::UtcNow.ToString('o');runFormal=[bool]$RunFormal;
    testsPassed=$false;preflightPassed=$false;formalPassed=0;rows=@();notExecuted=@();error=$null}
function Save-State {
    $state.rows=@($rows.ToArray())
    $state|ConvertTo-Json -Depth 10|Set-Content (Join-Path $output 'batch-status.json') -Encoding utf8
}
function Manifest {
    $files=@(Get-Item pom.xml)+@(Get-ChildItem src,scripts -Recurse -File|Where-Object FullName -notmatch '__pycache__')
    @($files|Sort-Object FullName|ForEach-Object {
        [ordered]@{path=[IO.Path]::GetRelativePath($root,$_.FullName);sha256=(Get-FileHash $_.FullName).Hash}
    })|ConvertTo-Json -Compress
}
function Check-Frozen {
    if((Get-FileHash $jar).Hash -ne $approvedHash -or (Manifest) -cne $frozen){throw '制品或源码变化，停止后续测量'}
}
function Run-Check([string]$Name,[string]$Exe,[string[]]$Arguments) {
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Exe;$info.WorkingDirectory=$root
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($a in $Arguments){$info.ArgumentList.Add($a)}
    @{executable=$Exe;arguments=$Arguments}|ConvertTo-Json|Set-Content (Join-Path $output "checks/$Name-command.json")
    $null=Invoke-ProcessWithEvidence -StartInfo $info -Prefix (Join-Path $output "checks/$Name") -TimeoutSeconds 120
}
function Run-Case([string]$Name,[string]$Scenario,[int]$Connections,[int]$Slow,[int]$Seconds,[int]$Warmup,[bool]$Formal) {
    Check-Frozen
    $directory=Join-Path $rawRoot $Name;$null=New-Item -ItemType Directory -Path $directory
    $row=[ordered]@{name=$Name;scenario=$Scenario;connections=$Connections;slowConnections=$Slow;
        seconds=$Seconds;warmupSeconds=$Warmup;formal=$Formal;directory=$directory;pid=$null;
        startedAt=[DateTimeOffset]::UtcNow.ToString('o');passed=$false;stopped=$false;error=$null}
    $server=[Diagnostics.Process]::new();$capture=$null;$started=$false
    $http=[Net.Http.HttpClient]::new();$gauges=@{};foreach($g in $gaugeNames){$gauges[$g]=$null}
    function Read-Json([string]$Path,[DateTimeOffset]$Deadline=[DateTimeOffset]::UtcNow.AddSeconds(2)) {
        $remaining=($Deadline-[DateTimeOffset]::UtcNow).TotalSeconds
        if($remaining -le 0){throw 'HTTP采集截止已到'}
        $cts=[Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds([Math]::Min(2,$remaining)))
        try {$http.GetStringAsync("http://127.0.0.1:$Port$Path",$cts.Token).GetAwaiter().GetResult()|ConvertFrom-Json}
        finally {$cts.Dispose()}
    }
    try {
        if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw '实验端口已占用'}
        $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$java;$info.WorkingDirectory=$root
        $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
        $arguments=@('-Xms128m','-Xmx256m','-jar',$jar,"--server.port=$Port",'--lab.cluster.mode=local',
            '--lab.outbound.mode=buffered','--lab.outbound.workers=8','--lab.dispatch.mode=fair')
        foreach($a in $arguments){$info.ArgumentList.Add($a)}
        $server.StartInfo=$info;$started=$server.Start();if(-not $started){throw 'JVM启动失败'}
        $row.pid=$server.Id;$capture=[DanmakuLab.ProcessTextCapture]::new($server)
        @{executable=$java;arguments=$arguments;pid=$server.Id;jarSha256=$approvedHash}|
            ConvertTo-Json|Set-Content (Join-Path $directory 'server-launch.json') -Encoding utf8
        $clock=[Diagnostics.Stopwatch]::StartNew()
        while($true){
            if($server.HasExited){throw 'JVM提前退出'}
            try {if((Read-Json '/actuator/health').status -eq 'UP'){break}}catch{}
            if($clock.Elapsed.TotalSeconds -ge 30){throw 'JVM未在30秒内就绪'}
            Start-Sleep -Milliseconds 200
        }
        $child=[Diagnostics.ProcessStartInfo]::new();$child.FileName=$pwsh;$child.WorkingDirectory=$root
        $child.UseShellExecute=$false;$child.CreateNoWindow=$true;$child.RedirectStandardOutput=$true;$child.RedirectStandardError=$true
        $argsList=@('-NoProfile','-File','scripts/run/longrun.ps1','-Scenario',$Scenario,'-Connections',"$Connections",
            '-SlowConnections',"$Slow",'-Seconds',"$Seconds",'-WarmupSeconds',"$Warmup",'-Rate','20','-SlowBytesPerSecond','1024',
            '-HttpUrl',"http://127.0.0.1:$Port",'-DispatchMode','fair','-JarPath',$jar,'-ExpectedJarHash',$approvedHash,
            '-OutputDirectory',(Join-Path $directory 'load'))
        foreach($a in $argsList){$child.ArgumentList.Add($a)}
        @{executable=$pwsh;arguments=$argsList}|ConvertTo-Json|Set-Content (Join-Path $directory 'command.json') -Encoding utf8
        Write-Host "LONG started: $Name; JVM=$($server.Id); normal=$($Connections-$Slow); slow=$Slow; warmup=$Warmup; measured=${Seconds}s"
        $progress=[pscustomobject]@{next=0.0}
        $null=Invoke-ProcessWithEvidence -StartInfo $child -Prefix (Join-Path $directory 'runner') -TimeoutSeconds ($Warmup+$Seconds+160) -OnTick {
            param($process,$elapsed)
            if($server.HasExited){throw '压测期间JVM退出'}
            if($elapsed -ge $progress.next){Write-Host "LONG running: $Name $([int]$elapsed)s";$progress.next=$elapsed+30}
        }
        $auditArgs=@('-X','utf8','scripts/tests/longrun-summary.test.py',(Join-Path $directory 'load'))
        if($Formal){$auditArgs+='--formal'}
        & python @auditArgs *> (Join-Path $directory 'audit-output.txt')
        if($LASTEXITCODE -ne 0){throw '离线长测审计失败'}
        $row.passed=$true
    }catch{$row.error=$_.Exception.Message}
    finally {
        # 外层也检查12项，覆盖子入口提前失败；未知/缺失必须让本轮失败。
        if($started -and -not $server.HasExited){
            $deadline=[DateTimeOffset]::UtcNow.AddSeconds(10)
            try {
                do {
                    foreach($gaugeName in $gaugeNames){
                        $g=Read-Json "/actuator/metrics/$gaugeName" $deadline
                        if($null -eq $g.measurements[0].value){throw "缺少Gauge $gaugeName"}
                        $gauges[$gaugeName]=$g.measurements[0].value
                    }
                    if(@($gauges.Values|Where-Object {$_ -ne 0}).Count -eq 0){break}
                    Start-Sleep -Milliseconds 100
                }while([DateTimeOffset]::UtcNow -lt $deadline)
            }catch{if(-not $row.error){$row.error=$_.Exception.Message}}
        }
        if(@($gaugeNames|Where-Object {$null -eq $gauges[$_] -or $gauges[$_] -ne 0}).Count){$row.passed=$false;if(-not $row.error){$row.error='外层12项Gauge未清理或未知'}}
        $gauges|ConvertTo-Json|Set-Content (Join-Path $directory 'outer-cleanup.json') -Encoding utf8
        if($started){if(-not $server.HasExited){$server.Kill($true)};$row.stopped=$server.WaitForExit(5000)}
        if(-not $row.stopped -or $row.error){$row.passed=$false}
        if($capture){$null=$capture.Completion.Wait(5000)
            $capture.StandardOutput|Set-Content (Join-Path $directory 'server-stdout.txt') -Encoding utf8
            $capture.StandardError|Set-Content (Join-Path $directory 'server-stderr.txt') -Encoding utf8}
        $http.Dispose();$server.Dispose();$row.endedAt=[DateTimeOffset]::UtcNow.ToString('o')
        $row|ConvertTo-Json|Set-Content (Join-Path $directory 'case-status.json') -Encoding utf8
    }
    $rows.Add([pscustomobject]$row);Save-State
    & python -X utf8 scripts/tests/repeat_longrun_report.py $output
    if($LASTEXITCODE -ne 0){throw '证据归档失败'}
    Write-Host "LONG result: $($row.name) passed=$($row.passed)"
    Check-Frozen
    [pscustomobject]$row
}
try {
    $approvedHash='BC5652880AD8FA34B903B7F6350E292D090FA914C85456879243082A38F43F65'
    $jar=(Resolve-Path $JarPath).Path
    if((Get-FileHash $jar).Hash -ne $approvedHash){throw '不是批准的候选JAR'}
    if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw '实验端口占用，不停止已有服务'}
    if(-not $OutputDirectory){$OutputDirectory="docs/results/$([DateTime]::Now.ToString('yyyy-MM-dd'))/longrun-repeat-$([DateTime]::Now.ToString('HHmmss'))"}
    if(Test-Path $OutputDirectory){throw '结果目录已存在，拒绝覆盖'}
    $null=New-Item -ItemType Directory -Path $OutputDirectory;$output=(Resolve-Path $OutputDirectory).Path
    $null=New-Item -ItemType Directory -Path (Join-Path $output 'checks')
    $rawRoot=Join-Path $root ".lab-artifacts/longrun-$([Guid]::NewGuid().ToString('N'))"
    $null=New-Item -ItemType Directory -Path $rawRoot;$state.rawRoot=$rawRoot;$state.jarSha256=$approvedHash
    $gaugeNames=@('lab.connections','lab.rooms','lab.outbound.bytes','lab.outbound.messages','lab.outbound.slots',
        'lab.outbound.tasks','lab.outbound.writers.active','lab.outbound.writes.inflight',
        'lab.dispatch.bytes','lab.dispatch.messages','lab.dispatch.rooms','lab.dispatch.ready')
    $settings=(& java '-XshowSettings:properties' '-version' 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $settings -notmatch '(?m)^\s*java\.home\s*=\s*(.+)\r?$'){throw '实际JDK路径未知'}
    $java=(Resolve-Path (Join-Path $Matches[1].Trim() 'bin/java.exe')).Path;$pwsh=(Get-Command pwsh).Source
    $awake=New-ExperimentAwake
    @{script=$PSCommandPath;parameters=$PSBoundParameters;jar=$jar;jarSha256=$approvedHash;java=$java;port=$Port}|
        ConvertTo-Json -Depth 6|Set-Content (Join-Path $output 'batch-command.json') -Encoding utf8
    $frozen=Manifest;$frozen|Set-Content (Join-Path $output 'frozen-source.json') -Encoding utf8
    # 源码快照与哈希一起保存，后续工具演进仍可复现当前测量，不要求未来工作区永远不变。
    & python -X utf8 scripts/tests/repeat_longrun_report.py $output --freeze
    if($LASTEXITCODE -ne 0){throw '源码归档失败'}
    Save-State
    Run-Check 'python' (Get-Command python).Source @('-X','utf8','-m','unittest','discover','-s','scripts/tests','-p','test_*.py','-v')
    Run-Check 'metrics' $pwsh @('-NoProfile','-File','scripts/tests/metrics.test.ps1')
    Run-Check 'process' $pwsh @('-NoProfile','-File','scripts/tests/process-evidence.test.ps1')
    Run-Check 'repeat-gates' $pwsh @('-NoProfile','-File','scripts/tests/repeat-longrun.test.ps1')
    # 历史复核只读，不把旧制品成绩算进本批三次独立重复。
    Run-Check 'historical-T3' (Get-Command python).Source @('-X','utf8','scripts/tests/longrun-summary.test.py','docs/results/2026-10-26/writer-fix-formal-t3-8writers-01','--formal')
    Run-Check 'historical-T5' (Get-Command python).Source @('-X','utf8','scripts/tests/longrun-summary.test.py','docs/results/2026-10-26/t5-094730','--formal')
    $state.testsPassed=$true;Save-State
    $preflight=@(
        @('smoke-T3','T3',20,10,10,2),@('smoke-T5','T5',20,0,10,2),
        @('scale-T3','T3',1000,10,20,5),@('scale-T5','T5',1000,0,20,5))
    foreach($p in $preflight){$r=Run-Case $p[0] $p[1] $p[2] $p[3] $p[4] $p[5] $false;if(-not $r.passed){throw "前置验收失败：$($p[0])"}}
    $state.preflightPassed=$true;Save-State
    $formalPlan=@('T3','T3','T3','T5','T5','T5')
    if($RunFormal){
        for($i=0;$i -lt 6;$i++){
            if(Test-Path (Join-Path $output 'formal-hold.json')){$state.runFormal=$false;break}
            $scenario=$formalPlan[$i];$seconds=if($scenario -eq 'T3'){900}else{1800};$slow=if($scenario -eq 'T3'){10}else{0}
            $r=Run-Case "formal-$($i+1)-$scenario" $scenario 1000 $slow $seconds 60 $true
            if(-not $r.passed){throw "正式轮失败，停止后续：$($r.name)"}
            $state.formalPassed++;Save-State
        }
    }
}catch{$failure=$_;$state.error=$_.Exception.Message}
finally {
    if($awake){$awake.Dispose()}
    if($output){
        $ran=@($rows|Where-Object formal).Count
        $plan=@('T3','T3','T3','T5','T5','T5');$state.notExecuted=@()
        for($i=$ran;$i -lt 6;$i++){$state.notExecuted+=@("formal-$($i+1)-$($plan[$i])")}
        $state.endedAt=[DateTimeOffset]::UtcNow.ToString('o');$state.awakeReleased=($null -ne $awake -and $awake.Released)
        Save-State
        & python -X utf8 scripts/tests/repeat_longrun_report.py $output
        Write-Host "LONG finished: formal=$($state.formalPassed)/6; $output"
    }
    Pop-Location
}
if($failure){throw $failure}
if($RunFormal -and $state.formalPassed -ne 6){exit 1}
