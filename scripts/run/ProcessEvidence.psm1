#requires -Version 7.0
# 子进程证据工具：无论正常结束、超时还是采样回调抛错，都先收尾并归档，再把原错误交还调用方。
# C# 异步读取器只处理管道，不调用 PowerShell；避免后台线程没有 PowerShell Runspace 的问题。
# 每读取一块文本就放进并发队列，所以即使子孙进程占着管道、ReadToEnd 永不结束，也能保存已读诊断。
if (-not ('DanmakuLab.ProcessTextCapture' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;

namespace DanmakuLab {
    public sealed class ProcessTextCapture {
        private readonly ConcurrentQueue<string> output = new ConcurrentQueue<string>();
        private readonly ConcurrentQueue<string> error = new ConcurrentQueue<string>();
        private readonly ConcurrentQueue<string> failures = new ConcurrentQueue<string>();
        public Task Completion { get; }
        public string StandardOutput => string.Concat(output.ToArray());
        public string StandardError => string.Concat(error.ToArray());
        public string[] Failures => failures.ToArray();

        public ProcessTextCapture(Process process) {
            Completion = Task.WhenAll(Read(process.StandardOutput, output), Read(process.StandardError, error));
        }

        // ReadAsync 持续排空两根管道，防止压测进程因输出管道满而反过来阻塞。
        private async Task Read(StreamReader reader, ConcurrentQueue<string> chunks) {
            var buffer = new char[1024];
            try {
                int count;
                while ((count = await reader.ReadAsync(buffer, 0, buffer.Length).ConfigureAwait(false)) != 0)
                    chunks.Enqueue(new string(buffer, 0, count));
            } catch (Exception exception) {
                failures.Enqueue(exception.GetType().Name + ": " + exception.Message);
            }
        }
    }
}
'@
}

function Invoke-ProcessWithEvidence {
    <#
    StartInfo 必须重定向 stdout/stderr；Prefix 是归档文件前缀，调用方负责准备父目录。
    OnTick 接收进程和已运行秒数，用于采样；其异常同样触发终止和归档，不会被吞掉。
    TimeoutSeconds 是压测运行截止；CleanupTimeoutSeconds 是整段退出/管道排空的额外等待预算。
    返回状态对象；失败时先写三个证据文件，再抛最初的 ErrorRecord，不继续下一组实验。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][Diagnostics.ProcessStartInfo]$StartInfo,
        [Parameter(Mandatory)][string]$Prefix,
        [Parameter(Mandatory)][ValidateRange(0.01, 86400)][double]$TimeoutSeconds,
        [scriptblock]$OnTick,
        [ValidateRange(10, 1000)][int]$PollMilliseconds = 100,
        [ValidateRange(0.1, 30)][double]$CleanupTimeoutSeconds = 5
    )
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $StartInfo
    $capture = $null
    $started = $false
    $originalError = $null
    $cleanupErrors = [Collections.Generic.List[string]]::new()
    $status = [ordered]@{
        started = $false; processId = $null; exitCode = $null; timedOut = $false; elapsedSeconds = 0
        error = $null; captureComplete = $false; captureErrors = @(); cleanupErrors = @()
    }
    $clock = [Diagnostics.Stopwatch]::StartNew()
    try {
        if (-not $process.Start()) { throw '子进程未能启动。' }
        $started = $true
        $status.started = $true
        $status.processId = $process.Id
        $capture = [DanmakuLab.ProcessTextCapture]::new($process)
        while (-not $process.HasExited) {
            if ($clock.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
                $status.timedOut = $true
                throw "子进程超过 $TimeoutSeconds 秒截止时间；保存证据后停止实验。"
            }
            if ($OnTick) { & $OnTick $process $clock.Elapsed.TotalSeconds | Out-Null }
            Start-Sleep -Milliseconds $PollMilliseconds
        }
        $status.exitCode = $process.ExitCode
        if ($process.ExitCode -ne 0) { throw "子进程退出码为 $($process.ExitCode)。" }
    } catch {
        $originalError = $_
        $status.error = $_.Exception.Message
    } finally {
        # Kill(true) 连同派生进程一起终止，避免它们继续施压或持有 stdout/stderr 管道。
        # 使用统一 Stopwatch 预算；WaitForExit 和管道等待加起来也不能无限延长退出。
        $cleanupClock = [Diagnostics.Stopwatch]::StartNew()
        if ($started) {
            try {
                if (-not $process.HasExited) { $process.Kill($true) }
                $waitMillis = [Math]::Max(0, [int](($CleanupTimeoutSeconds - $cleanupClock.Elapsed.TotalSeconds) * 1000))
                if (-not $process.WaitForExit($waitMillis)) { $cleanupErrors.Add('终止请求后，子进程仍未在清理预算内退出。') }
                if ($process.HasExited) { $status.exitCode = $process.ExitCode }
            } catch { $cleanupErrors.Add($_.Exception.Message) }
        }
        if ($capture) {
            $waitMillis = [Math]::Max(0, [int](($CleanupTimeoutSeconds - $cleanupClock.Elapsed.TotalSeconds) * 1000))
            $status.captureComplete = $capture.Completion.Wait($waitMillis)
            if (-not $status.captureComplete) { $cleanupErrors.Add('输出管道未完全关闭；已保存当前捕获的文本。') }
            $status.captureErrors = @($capture.Failures)
        }
        $status.elapsedSeconds = $clock.Elapsed.TotalSeconds
        $status.cleanupErrors = @($cleanupErrors.ToArray())
        # 分别尝试写入，不能 stdout 写入失败就跳过 stderr/status；归档错误也不掩盖原始压测错误。
        foreach ($streamName in @('stdout', 'stderr', 'status')) {
            try {
                if ($streamName -eq 'status') { $text = $status | ConvertTo-Json -Depth 6 }
                elseif ($streamName -eq 'stdout') { $text = $(if ($capture) { $capture.StandardOutput } else { '' }) }
                else { $text = $(if ($capture) { $capture.StandardError } else { '' }) }
                Set-Content -LiteralPath "$Prefix-$streamName.$(if ($streamName -eq 'status') { 'json' } else { 'txt' })" -Value $text -Encoding utf8
            } catch {
                if (-not $originalError) { $originalError = $_ }
                else { Write-Warning "归档 $streamName 失败：$($_.Exception.Message)" }
            }
        }
        $process.Dispose()
    }
    if ($originalError) { throw $originalError }
    if ($cleanupErrors.Count -or $status.captureErrors.Count) { throw "子进程证据收尾异常，请查看 $Prefix-status.json。" }
    [pscustomobject]$status
}

Export-ModuleMember -Function Invoke-ProcessWithEvidence
