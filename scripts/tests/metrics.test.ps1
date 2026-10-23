#requires -Version 7.0
# 从真实入口的 AST（解析后的语法树）取出指标读取函数，避免运行整个压测或复制实现。
# 构造缺失/零值快照，验证“未知不能冒充零”和尚未创建的事件计数器口径。
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../run/baseline.ps1'
$tokens = $null
$parseErrors = $null
$tree = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $source), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
$function = $tree.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Metric-Value'
}, $true)
if (-not $function) { throw 'Metric-Value 函数不存在' }
# 参数写在函数名之后，不在 Body 内；取完整函数定义，保留原始参数绑定。
. ([scriptblock]::Create($function.Extent.Text))

function Expect-Failure([scriptblock]$Action) {
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw '本应失败的缺失指标读取却成功了' }
}

$empty = @{ metrics = @() }
$zero = @{ metrics = @(@{ name = 'lab.connections'; measurements = @(@{ statistic = 'VALUE'; value = 0 }) }) }
$broken = @{ metrics = @(@{ name = 'lab.writes.failed'; measurements = @() }) }
if ((Metric-Value $zero 'lab.connections') -ne 0) { throw '实测零值读取错误' }
Expect-Failure { Metric-Value $empty 'lab.connections' }
Expect-Failure { Metric-Value $empty 'lab.rooms' }
if ((Metric-Value $empty 'lab.writes.failed' 'COUNT' -AllowAbsent) -ne 0) { throw '未创建的事件计数应为零' }
Expect-Failure { Metric-Value $broken 'lab.writes.failed' 'COUNT' -AllowAbsent }
Write-Output 'Metric snapshot regression: 5 checks passed.'
