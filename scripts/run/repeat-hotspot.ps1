#requires -Version 7.4
<#
T4独立重复：每个模式每轮创建一个新JVM，顺序施压，复用相同不可变JAR和负载参数。
只占用明确指定的实验端口；不停止已有服务。每组轮换direct/fair先后，保留运行顺序供判断偏差。
短轮只验证编排，不计正式重复；默认每阶段180秒、3对，约75分钟，不能并发运行来缩短时间。
#>
param(
    [ValidateRange(1,10)][int]$Rounds=3,
    [ValidateRange(2,180)][int]$PhaseSeconds=180,
    [ValidateRange(1,10)][int]$NormalRooms=10,
    [ValidateRange(2,100)][int]$NormalConnections=50,
    # 固定热点最高200/s，每发送者目标不超过4/s，因此至少50个热点连接。
    [ValidateRange(50,1500)][int]$HotConnections=500,
    [ValidateRange(10,120)][int]$StartDelaySeconds=30,
    [ValidateRange(1,100)][int]$LatencySampleEvery=10,
    [ValidateRange(1024,65535)][int]$Port=8085,
    [string]$JarPath='target/danmaku-lab-release.jar',
    [string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Import-Module (Join-Path $PSScriptRoot 'ProcessEvidence.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'ExperimentAwake.psm1') -Force
Push-Location $root
$rows=[Collections.Generic.List[object]]::new()
$failure=$null
$awake=$null
# 明确初始化脚本本地变量，避免早期校验失败时误用调用者作用域中同名$output写入别处。
$output=$null
try {
    $jar=(Resolve-Path -LiteralPath $JarPath).Path
    $jarHash=(Get-FileHash -LiteralPath $jar).Hash
    # 捕获记录前的Git状态；新建的证据目录不能反过来改变“测的是哪个源码状态”的含义。
    $sourceCommit=(& git -c safe.directory=D:/coding/DanmakuLab rev-parse HEAD)
    if($LASTEXITCODE -ne 0){throw '无法读取源码提交'}
    $sourceStatus=@(& git -c safe.directory=D:/coding/DanmakuLab status --porcelain)
    if($LASTEXITCODE -ne 0){throw '无法读取工作区状态'}
    if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "端口$Port已有服务，拒绝占用"}
    if(-not $OutputDirectory){
        $now=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'China Standard Time')
        $OutputDirectory="docs/results/$($now.ToString('yyyy-MM-dd'))/t4-repeats-$($now.ToString('HHmmss'))"
    }
    if(Test-Path -LiteralPath $OutputDirectory){throw '重复实验目录已存在，拒绝覆盖'}
    $null=New-Item -ItemType Directory -Path $OutputDirectory
    $output=(Resolve-Path -LiteralPath $OutputDirectory).Path
    $formal=$Rounds -ge 3 -and $PhaseSeconds -eq 180 -and $NormalRooms -eq 10 -and
        $NormalConnections -eq 50 -and $HotConnections -eq 500 -and $StartDelaySeconds -eq 30 -and $LatencySampleEvery -eq 10
    @{rounds=$Rounds;phaseSeconds=$PhaseSeconds;normalRooms=$NormalRooms;normalConnections=$NormalConnections;
      hotConnections=$HotConnections;startDelaySeconds=$StartDelaySeconds;latencySampleEvery=$LatencySampleEvery;
      port=$Port;jar=$jar;jarSha256=$jarHash;sourceCommit=$sourceCommit;sourceStatus=$sourceStatus;formal=$formal;
      note='Fresh JVM per run; sequential; same-host loopback; alternating pair order is recorded, not a claim of fully balanced randomization.'} |
        ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'batch-config.json') -Encoding utf8
    # 这是脚本自己的完整命令记录，不包含环境机密；业务与工具源码另由每次hotspot运行保存哈希。
    @{script=$PSCommandPath;parameters=$PSBoundParameters} | ConvertTo-Json -Depth 6 |
        Set-Content (Join-Path $output 'batch-command.json') -Encoding utf8
    # Oracle javapath可能是启动包装器，真正JVM另有PID；直接启动实际java.home下的程序，
    # 让持有的Process对象与监听端口、资源采样中的JVM一致，清理时也只操作这个进程树。
    $javaSettings=(& java '-XshowSettings:properties' '-version' 2>&1 | Out-String)
    if($LASTEXITCODE -ne 0 -or $javaSettings -notmatch '(?m)^\s*java\.home\s*=\s*(.+)\r?$'){throw '无法确定实际JDK目录'}
    $java=(Resolve-Path -LiteralPath (Join-Path $Matches[1].Trim() 'bin/java.exe')).Path
    $awake=New-ExperimentAwake
    $pwsh=(Get-Command pwsh).Source
    for($round=1;$round -le $Rounds;$round++){
        $modes=if($round%2 -eq 1){@('direct','fair')}else{@('fair','direct')}
        foreach($mode in $modes){
            if((Get-FileHash -LiteralPath $jar).Hash -ne $jarHash){throw '实验制品发生变化，停止后续对照'}
            if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "端口$Port仍被占用，拒绝开始下一组"}
            $name="round-$($round.ToString('00'))-$mode"
            $directory=Join-Path $output $name
            $null=New-Item -ItemType Directory -Path $directory
            $server=[Diagnostics.Process]::new()
            $capture=$null
            $started=$false
            $row=[ordered]@{round=$round;mode=$mode;directory=$name;startedAt=[DateTimeOffset]::UtcNow.ToString('o');
                processId=$null;passed=$false;stopped=$false;error=$null}
            $web=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
            try {
                # ArgumentList逐项传参，避免路径空格或特殊字符被shell二次解释；窗口保持隐藏。
                $info=[Diagnostics.ProcessStartInfo]::new()
                $info.FileName=$java; $info.WorkingDirectory=$root; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
                $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
                $arguments=@('-Xms128m','-Xmx256m','-jar',$jar,"--server.port=$Port",'--lab.cluster.mode=local',
                    '--lab.outbound.mode=buffered',"--lab.dispatch.mode=$mode")
                foreach($argument in $arguments){$info.ArgumentList.Add($argument)}
                $server.StartInfo=$info
                if(-not $server.Start()){throw '自建JVM启动失败'}
                $started=$true; $row.processId=$server.Id
                $capture=[DanmakuLab.ProcessTextCapture]::new($server)
                @{executable=$java;arguments=$arguments;pid=$server.Id;jarSha256=$jarHash} | ConvertTo-Json -Depth 4 |
                    Set-Content (Join-Path $directory 'server-launch.json') -Encoding utf8
                $ready=[Diagnostics.Stopwatch]::StartNew()
                while($true){
                    if($server.HasExited){throw "自建JVM提前退出：$($server.ExitCode)"}
                    try {
                        if((Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -WebSession $web -TimeoutSec 2).status -eq 'UP'){break}
                    } catch { if($ready.Elapsed.TotalSeconds -ge 30){throw '自建JVM未在30秒内就绪'} }
                    if($ready.Elapsed.TotalSeconds -ge 30){throw '自建JVM健康状态未在30秒内变为UP'}
                    Start-Sleep -Milliseconds 200
                }
                Write-Host "REPEAT $name started; PID=$($server.Id); phase=${PhaseSeconds}s; evidence=$directory"
                $child=[Diagnostics.ProcessStartInfo]::new()
                $child.FileName=$pwsh; $child.WorkingDirectory=$root; $child.UseShellExecute=$false; $child.CreateNoWindow=$true
                $child.RedirectStandardOutput=$true; $child.RedirectStandardError=$true
                $childArguments=@('-NoProfile','-File','scripts/run/hotspot.ps1','-SplitProcesses','-PhaseSeconds',"$PhaseSeconds",
                    '-NormalRooms',"$NormalRooms",'-NormalConnections',"$NormalConnections",'-HotConnections',"$HotConnections",
                    '-StartDelaySeconds',"$StartDelaySeconds",'-LatencySampleEvery',"$LatencySampleEvery",'-Mode','buffered',
                    '-DispatchMode',$mode,'-HttpUrl',"http://127.0.0.1:$Port",'-JarPath',$jar,'-OutputDirectory',(Join-Path $directory 'load'))
                foreach($argument in $childArguments){$child.ArgumentList.Add($argument)}
                $progress=[pscustomobject]@{next=0.0}
                $null=Invoke-ProcessWithEvidence -StartInfo $child -Prefix (Join-Path $directory 'runner') `
                    -TimeoutSeconds ($StartDelaySeconds+4*$PhaseSeconds+100) -OnTick {
                        param($process,$elapsed)
                        if($server.HasExited){throw '压测期间自建JVM退出'}
                        if($elapsed -ge $progress.next){Write-Host "REPEAT $name running: $([int]$elapsed)s"; $progress.next=$elapsed+30}
                    }
                # 旧入口的布尔清理结果不足以替代逐项检查；本版在关JVM前核对完整12个Gauge。
                $gauges=@{}
                foreach($metric in 'lab.connections','lab.rooms','lab.outbound.bytes','lab.outbound.messages','lab.outbound.slots',
                        'lab.outbound.tasks','lab.outbound.writers.active','lab.outbound.writes.inflight',
                        'lab.dispatch.bytes','lab.dispatch.messages','lab.dispatch.rooms','lab.dispatch.ready'){
                    $gauges[$metric]=(Invoke-RestMethod "http://127.0.0.1:$Port/actuator/metrics/$metric" -WebSession $web -TimeoutSec 3).measurements[0].value
                    if($null -eq $gauges[$metric] -or $gauges[$metric] -ne 0){throw "结束后$metric未归零"}
                }
                $gauges | ConvertTo-Json | Set-Content (Join-Path $directory 'cleanup-gauges.json') -Encoding utf8
                if((Get-FileHash -LiteralPath $jar).Hash -ne $jarHash){throw '运行后制品哈希发生变化'}
                $row.passed=$true
                Write-Host "REPEAT $name passed; independent summary validation follows the batch."
            } catch { $row.error=$_.Exception.Message; throw }
            finally {
                # 只持有本次Start创建的Process对象，不按端口搜索后随意停止外部JVM。
                if($started){
                    if(-not $server.HasExited){$server.Kill($true)}
                    $row.stopped=$server.WaitForExit(5000)
                }
                if($capture){
                    $null=$capture.Completion.Wait(5000)
                    $capture.StandardOutput | Set-Content (Join-Path $directory 'server-stdout.txt') -Encoding utf8
                    $capture.StandardError | Set-Content (Join-Path $directory 'server-stderr.txt') -Encoding utf8
                }
                $row.endedAt=[DateTimeOffset]::UtcNow.ToString('o')
                $rows.Add([pscustomobject]$row)
                $row | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $directory 'run-status.json') -Encoding utf8
                $web.Dispose(); $server.Dispose()
            }
        }
    }
} catch { $failure=$_ }
finally {
    if($awake){
        $clearError=$null
        try {$awake.Dispose()} catch {$clearError=$_.Exception.Message; if(-not $failure){$failure=$_}}
        @{requested=$true;released=$awake.Released;error=$clearError;note='SystemRequired only; manual sleep/reboot still invalidates a run.'} |
            ConvertTo-Json | Set-Content (Join-Path $output 'awake-status.json') -Encoding utf8
    }
    if($output){
        @{passed=($null -eq $failure);completed=$rows.Count;runs=@($rows.ToArray());
          error=$(if($failure){$failure.Exception.Message}else{$null})} | ConvertTo-Json -Depth 6 |
            Set-Content (Join-Path $output 'batch-status.json') -Encoding utf8
    }
    Pop-Location
}
if($failure){throw $failure}
Write-Output "T4 repetition batch completed: $output"
