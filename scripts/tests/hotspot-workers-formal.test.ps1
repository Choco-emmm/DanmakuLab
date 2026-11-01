#requires -Version 7.4
# 提取真实入口的正式for循环，在Run-Case边界替换网络操作；验证暂停、失败即停和固定顺序。
# 这不是负载成绩，不启动JVM，也不能替代真实正式六轮。
$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$source=(Resolve-Path (Join-Path $PSScriptRoot '../run/hotspot-workers.ps1')).Path
$tree=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw ($errors|Out-String)}
$loop=$tree.Find({param($node) $node -is [Management.Automation.Language.ForStatementAst] -and
    $node.Extent.Text.Contains('$r=Run-Case')},$true)
if(-not $loop){throw '找不到真实正式执行循环'}
$code=[scriptblock]::Create($loop.Extent.Text)
$output=Join-Path ([IO.Path]::GetTempPath()) "lab-formal-$([Guid]::NewGuid().ToString('N'))"
$null=New-Item -ItemType Directory -Path $output
$formalModes=@('direct','fair','fair','direct','direct','fair')
function Save-State {}
function Run-Case {
    param($Name,$Workers,$Seconds,$Small,$Mode,$Stall,$Diagnostic)
    $calls.Add($Mode)
    if($injectHold -and $calls.Count -eq 1){'{}'|Set-Content (Join-Path $output 'formal-hold.json')}
    [pscustomobject]@{passed=(-not $injectFailure)}
}
# 第一轮结束后撤回授权，下一轮不得启动；不靠杀进程来猜测启动时序。
$calls=[Collections.Generic.List[string]]::new();$injectHold=$true;$injectFailure=$false
$state=@{runFormal=$true;formalCompleted=0;gateReasons=@()}
& $code
if($calls.Count -ne 1 -or $state.runFormal -or $state.formalCompleted -ne 1){throw '中途暂停未在下一轮前生效'}

# 已有暂停标记时零启动。标记文件保留在临时证据目录，便于失败排查。
$calls.Clear();$injectHold=$false;$state=@{runFormal=$true;formalCompleted=0;gateReasons=@()}
& $code
if($calls.Count -ne 0){throw '暂停标记被忽略'}

# 后面两个场景用新目录，不删除或覆盖前两个场景的暂停证据。
$output=Join-Path $output 'no-hold';$null=New-Item -ItemType Directory -Path $output
$calls.Clear();$injectFailure=$true;$state=@{runFormal=$true;formalCompleted=0;gateReasons=@()}
& $code
if($calls.Count -ne 1 -or $state.formalCompleted -ne 0){throw '正式失败后仍继续或误计通过'}

$calls.Clear();$injectFailure=$false;$state=@{runFormal=$true;formalCompleted=0;gateReasons=@()}
& $code
if(($calls -join ',') -ne ($formalModes -join ',') -or $state.formalCompleted -ne 6){throw '固定正式顺序或次数错误'}
Write-Output 'Formal gate regression: 4 scenarios passed.'
