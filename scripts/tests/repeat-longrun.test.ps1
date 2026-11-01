#requires -Version 7.4
# 在真实正式循环的Run-Case边界替换网络操作，检查固定次数/参数、失败即停和撤回标记。
$ErrorActionPreference='Stop';$tokens=$null;$errors=$null
$source=(Resolve-Path (Join-Path $PSScriptRoot '../run/repeat-longrun.ps1')).Path
$tree=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw ($errors|Out-String)}
$caseFunction=$tree.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Run-Case'},$true)
$parameterNames=@($caseFunction.Parameters|ForEach-Object {$_.Name.VariablePath.UserPath})
# PowerShell变量名不区分大小写：foreach的$name会覆盖参数$Name，即使结构化归档先保存了正确名称。
$collisions=@($caseFunction.Body.FindAll({param($n) $n -is [Management.Automation.Language.ForEachStatementAst]},$true)|
    Where-Object {$_.Variable.VariablePath.UserPath -in $parameterNames})
if($collisions.Count){throw '轮次函数中的循环变量覆盖了参数，可能破坏日志或后续控制'}
$loop=$tree.Find({param($n) $n -is [Management.Automation.Language.ForStatementAst] -and $n.Extent.Text.Contains('$r=Run-Case')},$true)
if(-not $loop){throw '正式循环不存在'}
$code=[scriptblock]::Create($loop.Extent.Text)
$output=Join-Path ([IO.Path]::GetTempPath()) "longrun-loop-$([Guid]::NewGuid().ToString('N'))"
$null=New-Item -ItemType Directory -Path $output
$formalPlan=@('T3','T3','T3','T5','T5','T5')
function Save-State {}
function Run-Case {
    param($Name,$Scenario,$Connections,$Slow,$Seconds,$Warmup,$Formal)
    if($Connections -ne 1000 -or $Warmup -ne 60 -or -not $Formal){throw '正式参数被缩小'}
    if(($Scenario -eq 'T3' -and ($Slow -ne 10 -or $Seconds -ne 900)) -or
       ($Scenario -eq 'T5' -and ($Slow -ne 0 -or $Seconds -ne 1800))){throw '正式场景或时长错误'}
    $calls.Add($Scenario)
    if($hold -and $calls.Count -eq 1){'{}'|Set-Content (Join-Path $output 'formal-hold.json')}
    [pscustomobject]@{name=$Name;passed=(-not $fail)}
}
$calls=[Collections.Generic.List[string]]::new();$fail=$false;$hold=$false;$state=@{formalPassed=0;runFormal=$true}
& $code
if(($calls -join ',') -ne ($formalPlan -join ',') -or $state.formalPassed -ne 6){throw '次数/顺序不符'}
$calls.Clear();$fail=$true;$state.formalPassed=0;$caught=$false
try{& $code}catch{$caught=$true}
if(-not $caught -or $calls.Count -ne 1 -or $state.formalPassed -ne 0){throw '失败后仍继续或误计成功'}
$calls.Clear();$fail=$false;$hold=$true;$state.formalPassed=0
& $code
if($calls.Count -ne 1 -or $state.runFormal -or $state.formalPassed -ne 1){throw '撤回标记未阻止后续轮'}
Write-Output 'Longrun repeat regression: 3 scenarios passed.'
