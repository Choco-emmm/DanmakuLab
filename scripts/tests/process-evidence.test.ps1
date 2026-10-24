#requires -Version 7.0
# 使用真实短 Python 子进程验证管道捕获和失败收尾，不复制实现、不等待压测入口的 61 秒截止。
# 每个场景保留独立证据，断言日志可见、原错误保留、子进程已经退出。
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../run/ProcessEvidence.psm1') -Force
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$directory = Join-Path $root "target/process-evidence-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $directory | Out-Null

function Child-Info([string]$Code) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = (Get-Command python).Source
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.ArgumentList.Add('-c')
    $info.ArgumentList.Add($Code)
    $info
}

function Assert-Archived([string]$Prefix, [string]$OutputMarker, [string]$ErrorMarker) {
    foreach ($part in @('stdout.txt', 'stderr.txt', 'status.json')) {
        if (-not (Test-Path -LiteralPath "$Prefix-$part")) { throw "缺少失败证据：$Prefix-$part" }
    }
    if ((Get-Content -LiteralPath "$Prefix-stdout.txt" -Raw) -notmatch $OutputMarker) { throw '标准输出中的已发送诊断未保存。' }
    if ((Get-Content -LiteralPath "$Prefix-stderr.txt" -Raw) -notmatch $ErrorMarker) { throw '标准错误中的已发送诊断未保存。' }
    $status = Get-Content -LiteralPath "$Prefix-status.json" -Raw | ConvertFrom-Json
    if ($status.processId -and (Get-Process -Id $status.processId -ErrorAction SilentlyContinue)) { throw '失败后子进程仍存活。' }
    $status
}

# 正常路径同样要保存两路输出，返回退出码，不能为了修复失败路径破坏原有成功行为。
$prefix = Join-Path $directory 'success'
$result = Invoke-ProcessWithEvidence -StartInfo (Child-Info 'import sys; print("success-out", flush=True); print("success-err", file=sys.stderr, flush=True)') -Prefix $prefix -TimeoutSeconds 3
$status = Assert-Archived $prefix 'success-out' 'success-err'
if ($result.exitCode -ne 0 -or $status.error -or -not $status.captureComplete) { throw '正常子进程被误判为失败。' }

# 真正运行 30 秒休眠进程，但入口 1 秒超时就杀掉它；因此本回归无需长时间等待。
$prefix = Join-Path $directory 'timeout'
$caught = $null
$watch = [Diagnostics.Stopwatch]::StartNew()
try {
    Invoke-ProcessWithEvidence -StartInfo (Child-Info 'import sys,time; print("timeout-out", flush=True); print("timeout-err", file=sys.stderr, flush=True); time.sleep(30)') -Prefix $prefix -TimeoutSeconds 1 -CleanupTimeoutSeconds 2 | Out-Null
} catch { $caught = $_ }
$status = Assert-Archived $prefix 'timeout-out' 'timeout-err'
if (-not $caught -or -not $status.timedOut -or $caught.Exception.Message -notmatch '截止时间') { throw '截止超时未保留原错误。' }
if ($watch.Elapsed.TotalSeconds -gt 5) { throw '超时收尾超过有限等待预算。' }

# 采样回调自身出错也必须终止进程、保留日志；让回调等半秒以保证 Python 已输出诊断。
$prefix = Join-Path $directory 'callback'
$caught = $null
try {
    Invoke-ProcessWithEvidence -StartInfo (Child-Info 'import sys,time; print("callback-out", flush=True); print("callback-err", file=sys.stderr, flush=True); time.sleep(30)') -Prefix $prefix -TimeoutSeconds 3 -OnTick {
        param($child, $elapsed)
        if ($elapsed -gt 0.5) { throw 'deliberate-collector-failure' }
    } | Out-Null
} catch { $caught = $_ }
$status = Assert-Archived $prefix 'callback-out' 'callback-err'
if (-not $caught -or $caught.Exception.Message -ne 'deliberate-collector-failure' -or $status.timedOut) { throw '采样错误被超时或清理异常覆盖。' }

# 子进程自己失败时，退出码和已输出的排错信息同样必须落盘。
$prefix = Join-Path $directory 'exit-code'
$caught = $null
try {
    Invoke-ProcessWithEvidence -StartInfo (Child-Info 'import sys; print("exit-out", flush=True); print("exit-err", file=sys.stderr, flush=True); sys.exit(7)') -Prefix $prefix -TimeoutSeconds 3 | Out-Null
} catch { $caught = $_ }
$status = Assert-Archived $prefix 'exit-out' 'exit-err'
if (-not $caught -or $status.exitCode -ne 7 -or $status.error -notmatch '7') { throw '非零退出码未归档为失败。' }

# 启动失败时没有可读管道，也仍需保存空日志和状态，便于区分“未启动”与“启动后超时”。
$prefix = Join-Path $directory 'start-failure'
$invalid = Child-Info 'pass'
$invalid.FileName = Join-Path $directory 'missing-executable.exe'
$caught = $null
try { Invoke-ProcessWithEvidence -StartInfo $invalid -Prefix $prefix -TimeoutSeconds 1 | Out-Null } catch { $caught = $_ }
$status = Assert-Archived $prefix '^\s*$' '^\s*$'
if (-not $caught -or $status.started -or -not $status.error) { throw '启动失败未正确归档。' }
Write-Output "Process evidence regression: 5 scenarios passed. Evidence: $directory"
