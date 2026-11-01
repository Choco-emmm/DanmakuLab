#requires -Version 7.4
<#
T4工作进程验收与固定重复入口。默认完成工具验收和四轮短对照；RunFormal明确授权
在门槛全部通过后自动接着跑六轮180秒正式实验。失败不重试，不修改Java业务或门槛。
大位图/日志留在忽略的独立原始目录，结果目录保留摘要、压缩索引和SHA-256。
#>
param([switch]$RunFormal,[string]$OutputDirectory,[string]$JarPath='target/danmaku-lab-release.jar',
      [ValidateRange(1024,65535)][int]$Port=8085)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Import-Module (Join-Path $PSScriptRoot 'ProcessEvidence.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'HotspotWorkerCase.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'ExperimentAwake.psm1') -Force
Push-Location $root
$awake=$null;$output=$null;$rawRoot=$null;$failure=$null
$rows=[Collections.Generic.List[object]]::new()
$state=[ordered]@{schemaVersion=1;startedAt=[DateTimeOffset]::UtcNow.ToString('o');runFormal=[bool]$RunFormal;
    testsPassed=$false;prerequisitesPassed=$false;formalCompleted=0;gateReasons=@();notExecuted=@();rows=@();error=$null}
function Save-State {
    $state.rows=@($rows.ToArray())
    $state|ConvertTo-Json -Depth 15|Set-Content (Join-Path $output 'batch-status.json') -Encoding utf8
}
function Source-Manifest {
    # 业务与工具一起冻结；缓存/结果不属于源码，不能因新建证据反过来改变被测源码身份。
    $files=@(Get-Item pom.xml)+@(Get-ChildItem src,scripts -Recurse -File|Where-Object FullName -notmatch '__pycache__')
    @($files|Sort-Object FullName|ForEach-Object {
        [ordered]@{path=[IO.Path]::GetRelativePath($root,$_.FullName);sha256=(Get-FileHash $_.FullName).Hash}
    })|ConvertTo-Json -Compress
}
function Check-Frozen {
    if((Get-FileHash -LiteralPath $jar).Hash -ne $approvedHash){throw 'JAR变化，停止实验'}
    if((Source-Manifest) -cne $frozen){throw '业务/工具源码变化，停止后续轮，不能混用版本'}
}
function Run-Check([string]$Name,[string]$Executable,[string[]]$Arguments) {
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Executable;$info.WorkingDirectory=$root
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($a in $Arguments){$info.ArgumentList.Add($a)}
    @{executable=$Executable;arguments=$Arguments}|ConvertTo-Json|Set-Content (Join-Path $output "checks/$Name-command.json")
    $null=Invoke-ProcessWithEvidence -StartInfo $info -Prefix (Join-Path $output "checks/$Name") -TimeoutSeconds 120
}
function Run-Case([string]$Name,[int]$Workers,[int]$Seconds,[bool]$Small,[string]$Mode,[int]$Stall=0,[bool]$Diagnostic=$true) {
    Check-Frozen
    $parameters=@{Root=$root;Directory=(Join-Path $rawRoot $Name);Jar=$jar;JarHash=$approvedHash;Java=$java;
        Port=$Port;Workers=$Workers;PhaseSeconds=$Seconds;Dispatch=$Mode;Diagnostic=$Diagnostic;StallMs=$Stall}
    if($Small){$parameters.NormalRooms=2;$parameters.NormalConnections=5;$parameters.HotConnections=50}
    $row=Invoke-HotspotWorkerCase @parameters
    $row|Add-Member -NotePropertyName name -NotePropertyValue $Name
    $rows.Add($row);Save-State
    # 每轮结束立即生成原始文件索引/压缩副本；压缩不在输入窗口内运行。
    & python -X utf8 scripts/tests/hotspot_workers_report.py $output
    if($LASTEXITCODE -ne 0){throw '证据归档失败；停止后续测量'}
    Write-Host "HW result: $Name passed=$($row.passed) evidenceComplete=$($row.evidenceComplete)"
    Check-Frozen
    $row
}
try {
    $approvedHash='BC5652880AD8FA34B903B7F6350E292D090FA914C85456879243082A38F43F65'
    $jar=(Resolve-Path -LiteralPath $JarPath).Path
    if((Get-FileHash $jar).Hash -ne $approvedHash){throw '制品不是已批准的发布JAR'}
    if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "端口$Port已占用"}
    if(-not $OutputDirectory){$OutputDirectory="docs/results/2026-10-27/t4-workers-$([DateTime]::Now.ToString('HHmmss'))"}
    if(Test-Path -LiteralPath $OutputDirectory){throw '结果目录已存在，拒绝覆盖'}
    $null=New-Item -ItemType Directory -Path $OutputDirectory;$output=(Resolve-Path $OutputDirectory).Path
    $rawRoot=Join-Path $root ".lab-artifacts/t4-workers-$([Guid]::NewGuid().ToString('N'))"
    $null=New-Item -ItemType Directory -Path $rawRoot
    $null=New-Item -ItemType Directory -Path (Join-Path $output 'checks')
    $state.rawRoot=$rawRoot;$state.jarSha256=$approvedHash;$state.port=$Port
    $settings=(& java '-XshowSettings:properties' '-version' 2>&1|Out-String)
    if($LASTEXITCODE -ne 0 -or $settings -notmatch '(?m)^\s*java\.home\s*=\s*(.+)\r?$'){throw '无法确定实际JDK目录'}
    $java=(Resolve-Path (Join-Path $Matches[1].Trim() 'bin/java.exe')).Path
    $awake=New-ExperimentAwake
    @{script=$PSCommandPath;parameters=$PSBoundParameters;rawRoot=$rawRoot;java=$java;jar=$jar;
      jarSha256=$approvedHash;sourceCommit=(& git -c safe.directory=D:/coding/DanmakuLab rev-parse HEAD);
      sourceStatus=@(& git -c safe.directory=D:/coding/DanmakuLab status --porcelain)}|
        ConvertTo-Json -Depth 8|Set-Content (Join-Path $output 'batch-command.json') -Encoding utf8
    $frozen=Source-Manifest;$frozen|Set-Content (Join-Path $output 'frozen-source.json') -Encoding utf8
    @{smoke=@(1,5);injection=@{workers=5;worker=0;phase=1;stallMs=1500};short=@(1,5,5,1);
      formal=@('direct','fair','fair','direct','direct','fair');formalPhaseSeconds=180;
      formalInputMinutes=72;startDelaySeconds=30;drainSeconds=5;closeMode='all';normal='10x50';hotConnections=500}|
        ConvertTo-Json -Depth 5|Set-Content (Join-Path $output 'fixed-plan.json') -Encoding utf8
    Save-State
    Run-Check 'python' (Get-Command python).Source @('-X','utf8','-m','unittest','discover','-s','scripts/tests','-p','test_*.py','-v')
    Run-Check 'node' (Get-Command node).Source @('--test','scripts/tests/hotspot-diagnostic.test.cjs','scripts/tests/hotspot-workers.test.cjs')
    Run-Check 'metrics' (Get-Command pwsh).Source @('-NoProfile','-File','scripts/tests/metrics.test.ps1')
    Run-Check 'process' (Get-Command pwsh).Source @('-NoProfile','-File','scripts/tests/process-evidence.test.ps1')
    Run-Check 'formal-gates' (Get-Command pwsh).Source @('-NoProfile','-File','scripts/tests/hotspot-workers-formal.test.ps1')
    foreach($mode in 'direct','fair'){
        Run-Check "historical-$mode" (Get-Command python).Source @('-X','utf8','scripts/tests/hotspot-summary.test.py',"docs/results/2026-10-27/t4-diagnostic-default-regression/round-01-$mode/load")
    }
    Run-Check 'historical-diagnostic' (Get-Command python).Source @('-X','utf8','scripts/tests/hotspot-summary.test.py','docs/results/2026-10-27/t4-diagnostic-resume-smoke/all-01/load')
    $state.testsPassed=$true;Save-State
    $smoke1=Run-Case 'smoke-1' 1 3 $true 'fair'
    $smoke5=Run-Case 'smoke-5' 5 3 $true 'fair'
    $injected=Run-Case 'injected-5' 5 3 $true 'fair' 1500
    $short=[Collections.Generic.List[object]]::new();$i=0
    # 短轮失败也必须继续全部四个预定尝试；不按结果选择1/5进程顺序。
    foreach($w in 1,5,5,1){$i++;$short.Add((Run-Case "short-$i-w$w" $w 10 $false 'fair'))}
    $reasons=[Collections.Generic.List[string]]::new()
    if(-not($smoke1.passed -and $smoke5.passed)){$reasons.Add('真实60观众冒烟未全部通过')}
    if(-not $injected.evidenceComplete){$reasons.Add('受控失败证据不完整')}
    else {
        $a=Get-Content (Join-Path $injected.directory 'audit.json') -Raw|ConvertFrom-Json
        $bad=@($a.errors|Where-Object {$_ -notin 'hot-0: thresholds','hot-0: independent send-lag check','hot-0: process failure'})
        if($injected.passed -or $a.workers[0].maxSendLagMs -lt 1000 -or -not $a.workers[0].injectionObserved -or
           @($a.workers|Select-Object -Skip 1|Where-Object injectionObserved).Count -or $bad.Count){
            $reasons.Add('受控停顿未被原门槛单独识别，或存在额外故障')
        }
    }
    foreach($s in $short){
        if(-not $s.evidenceComplete){$reasons.Add("$($s.name): 缺失证据，不能用于单进程基线结论")}
        if($s.workers -eq 5 -and -not $s.passed){$reasons.Add("$($s.name): 五热点进程千连接短测未通过")}
    }
    $state.gateReasons=@($reasons.ToArray());$state.prerequisitesPassed=$reasons.Count -eq 0;Save-State
    $formalModes=@('direct','fair','fair','direct','direct','fair')
    # 用户可以在长任务进行中撤回正式长测授权。外部只需写入此标记；
    # 不修改冻结源码、不打断当前短轮，也不依靠计时抢在正式JVM启动前杀进程。
    $formalHeld=Test-Path -LiteralPath (Join-Path $output 'formal-hold.json')
    if($formalHeld){$state.runFormal=$false;$state.gateReasons+=@('用户要求暂不执行正式长测');Save-State}
    if($RunFormal -and -not $formalHeld -and $state.prerequisitesPassed){
        for($n=0;$n -lt 6;$n++){
            # 每轮都重新检查撤回标记，保留已完成结果并在下一次创建JVM前停止。
            # 当前轮仍会正常排空和清理，不能把一轮结束后的暂停误写成性能失败。
            if(Test-Path -LiteralPath (Join-Path $output 'formal-hold.json')){
                $state.runFormal=$false;$state.gateReasons+=@('用户要求暂停后续正式长测');Save-State;break
            }
            $mode=$formalModes[$n];$pair=[int][Math]::Floor($n/2)+1
            $r=Run-Case "formal-$($n+1)-pair$pair-$mode" 5 180 $false $mode 0 $false
            if(-not $r.passed){$state.gateReasons+=@("正式第$($n+1)轮失败，停止后续正式轮");break}
            $state.formalCompleted++;Save-State
        }
    }
    $ran=@($rows|Where-Object name -like 'formal-*').Count
    for($n=$ran;$n -lt 6;$n++){$state.notExecuted+=@("formal-$($n+1)-$($formalModes[$n])")}
}catch{$failure=$_;$state.error=$_.Exception.Message}
finally{
    if($awake){$awake.Dispose()}
    if($output){
        if($state.notExecuted.Count -eq 0 -and $state.formalCompleted -lt 6){
            $state.notExecuted=@('direct','fair','fair','direct','direct','fair') | Select-Object -Skip @($rows|Where-Object name -like 'formal-*').Count
        }
        $state.endedAt=[DateTimeOffset]::UtcNow.ToString('o');$state.awakeReleased=($null -ne $awake -and $awake.Released)
        Save-State
        & python -X utf8 scripts/tests/hotspot_workers_report.py $output
        if($LASTEXITCODE -ne 0 -and -not $failure){$failure='最终归档失败'}
        Write-Host "HW finished: prerequisites=$($state.prerequisitesPassed); formal=$($state.formalCompleted)/6; $output"
    }
    Pop-Location
}
if($failure){throw $failure}
if(-not $state.prerequisitesPassed -or ($RunFormal -and $state.formalCompleted -ne 6)){exit 1}

