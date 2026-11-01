#requires -Version 7.4
<#
固定次数的T4短诊断：smoke为60人全关/分批各一次；injected为一次受控停顿；
compare为1000人四次all/batched/batched/all。失败不自动重试、不替换结果。
每次创建独立JVM，只占实验端口，不修改业务/JAR/限流；诊断绝不能计入正式三对验收。
#>
param(
    [ValidateSet('smoke','injected','compare')][string]$Suite='compare',
    [ValidateRange(1024,65535)][int]$Port=8085,
    [string]$JarPath='target/danmaku-lab-release.jar',
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Import-Module (Join-Path $PSScriptRoot 'ProcessEvidence.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'ExperimentAwake.psm1') -Force
Push-Location $root
$output=$null; $awake=$null
$rows=[Collections.Generic.List[object]]::new()
try {
    if(Test-Path -LiteralPath $OutputDirectory){throw '诊断目录已存在，拒绝覆盖'}
    if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw '实验端口已有服务，拒绝占用'}
    $jar=(Resolve-Path -LiteralPath $JarPath).Path
    $hash=(Get-FileHash -LiteralPath $jar).Hash
    $settings=(& java '-XshowSettings:properties' '-version' 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $settings -notmatch '(?m)^\s*java\.home\s*=\s*(.+)\r?$'){throw '无法确定实际JDK'}
    $java=(Resolve-Path (Join-Path $Matches[1].Trim() 'bin/java.exe')).Path
    $pwsh=(Get-Command pwsh).Source
    $null=New-Item -ItemType Directory -Path $OutputDirectory
    $output=(Resolve-Path -LiteralPath $OutputDirectory).Path
    $small=$Suite -ne 'compare'
    $phase=if($small){3}else{10}
    $startDelay=if($small){10}else{30}
    $normalRooms=if($small){2}else{10}
    $normalConnections=if($small){5}else{50}
    $hotConnections=if($small){50}else{500}
    # 四次在运行前固定，任何一轮负载失败也如实保留，不临时加跑“凑通过”。
    $cases=switch($Suite){
        'smoke' {@(@{name='all-01';batch=0;stall=0},@{name='batched-01';batch=10;stall=0})}
        'injected' {@(@{name='stall-01';batch=0;stall=1100})}
        'compare' {@(@{name='all-01';batch=0;stall=0},@{name='batched-01';batch=25;stall=0},
                     @{name='batched-02';batch=25;stall=0},@{name='all-02';batch=0;stall=0})}
    }
    @{suite=$Suite;cases=$cases;phaseSeconds=$phase;startDelaySeconds=$startDelay;normalRooms=$normalRooms;
      normalConnections=$normalConnections;hotConnections=$hotConnections;port=$Port;jar=$jar;jarSha256=$hash;
      formalEligible=$false;command=$PSBoundParameters;note='Fixed sequential short diagnostics; fresh JVM per case; no automatic retries.'} |
        ConvertTo-Json -Depth 6 | Set-Content (Join-Path $output 'suite-config.json') -Encoding utf8
    $awake=New-ExperimentAwake
    foreach($case in $cases){
        if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw '前一轮端口未释放，停止后续诊断'}
        if((Get-FileHash -LiteralPath $jar).Hash -ne $hash){throw 'JAR变化，停止对照'}
        $dir=Join-Path $output $case.name
        $null=New-Item -ItemType Directory -Path $dir
        $row=[ordered]@{name=$case.name;startedAt=[DateTimeOffset]::UtcNow.ToString('o');pid=$null;
            loadPassed=$false;evidenceComplete=$false;error=$null;stopped=$false}
        $server=[Diagnostics.Process]::new();$capture=$null;$started=$false
        $web=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
        try {
            $info=[Diagnostics.ProcessStartInfo]::new()
            $info.FileName=$java;$info.WorkingDirectory=$root;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
            $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
            $arguments=@('-Xms128m','-Xmx256m','-jar',$jar,"--server.port=$Port",'--lab.cluster.mode=local',
                '--lab.outbound.mode=buffered','--lab.dispatch.mode=fair')
            foreach($argument in $arguments){$info.ArgumentList.Add($argument)}
            $server.StartInfo=$info
            $started=$server.Start();if(-not $started){throw '自建服务启动失败'}
            $row.pid=$server.Id;$capture=[DanmakuLab.ProcessTextCapture]::new($server)
            @{pid=$server.Id;executable=$java;arguments=$arguments;jarSha256=$hash}|ConvertTo-Json -Depth 4|
                Set-Content (Join-Path $dir 'server-launch.json') -Encoding utf8
            $watch=[Diagnostics.Stopwatch]::StartNew()
            while($true){
                if($server.HasExited){throw '自建JVM提前退出'}
                try {if((Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -WebSession $web -TimeoutSec 2).status -eq 'UP'){break}}catch{}
                if($watch.Elapsed.TotalSeconds -gt 30){throw '服务未在30秒内就绪'}
                Start-Sleep -Milliseconds 200
            }
            $child=[Diagnostics.ProcessStartInfo]::new()
            $child.FileName=$pwsh;$child.WorkingDirectory=$root;$child.UseShellExecute=$false;$child.CreateNoWindow=$true
            $child.RedirectStandardOutput=$true;$child.RedirectStandardError=$true
            $argsList=@('-NoProfile','-File','scripts/run/hotspot.ps1','-Diagnostic','-SplitProcesses',
                '-PhaseSeconds',"$phase",'-StartDelaySeconds',"$startDelay",'-NormalRooms',"$normalRooms",
                '-NormalConnections',"$normalConnections",'-HotConnections',"$hotConnections",'-DrainSeconds','5',
                '-DiagnosticCloseBatchSize',"$($case.batch)",'-DiagnosticStallMs',"$($case.stall)",
                '-Mode','buffered','-DispatchMode','fair','-HttpUrl',"http://127.0.0.1:$Port",'-JarPath',$jar,
                '-OutputDirectory',(Join-Path $dir 'load'))
            foreach($argument in $argsList){$child.ArgumentList.Add($argument)}
            @{executable=$pwsh;arguments=$argsList}|ConvertTo-Json -Depth 4|Set-Content (Join-Path $dir 'command.json') -Encoding utf8
            Write-Output "DIAGNOSTIC $Suite/$($case.name) started; PID=$($server.Id)"
            try {
                $null=Invoke-ProcessWithEvidence -StartInfo $child -Prefix (Join-Path $dir 'runner') -TimeoutSeconds ($startDelay+4*$phase+65)
            } catch {$row.error=$_.Exception.Message}
            # --evidence-only只允许审计器退出0表示证据可读；loadPassed仍读取JSON，失败绝不翻转。
            & python -X utf8 scripts/tests/hotspot-diagnostic-summary.py (Join-Path $dir 'load') --evidence-only *> (Join-Path $dir 'audit-output.txt')
            if($LASTEXITCODE -eq 0){
                $audit=Get-Content (Join-Path $dir 'load/diagnostic-audit.json') -Raw|ConvertFrom-Json
                $row.evidenceComplete=$audit.evidenceComplete;$row.loadPassed=$audit.passed
            } else {if(-not $row.error){$row.error='诊断证据不完整，见audit-output.txt'}}
        } catch {$row.error=$_.Exception.Message}
        finally {
            # 仅停止本次持有的Process对象，不按名字结束用户的其他Java或k6进程。
            if($started){if(-not $server.HasExited){$server.Kill($true)};$row.stopped=$server.WaitForExit(5000)}
            if($capture){$null=$capture.Completion.Wait(5000);$capture.StandardOutput|Set-Content (Join-Path $dir 'server-stdout.txt') -Encoding utf8;
                $capture.StandardError|Set-Content (Join-Path $dir 'server-stderr.txt') -Encoding utf8}
            $row.endedAt=[DateTimeOffset]::UtcNow.ToString('o');$rows.Add([pscustomobject]$row)
            $row|ConvertTo-Json|Set-Content (Join-Path $dir 'case-status.json') -Encoding utf8
            $web.Dispose();$server.Dispose()
        }
        Write-Output "DIAGNOSTIC $($case.name): loadPassed=$($row.loadPassed); evidenceComplete=$($row.evidenceComplete)"
    }
} finally {
    if($awake){$awake.Dispose();@{released=$awake.Released}|ConvertTo-Json|Set-Content (Join-Path $output 'awake-status.json') -Encoding utf8}
    if($output){@{completed=$rows.Count;runs=@($rows.ToArray());formalEligible=$false}|ConvertTo-Json -Depth 6|
        Set-Content (Join-Path $output 'suite-status.json') -Encoding utf8}
    Pop-Location
}
