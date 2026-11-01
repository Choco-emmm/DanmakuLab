#requires -Version 7.4
# 正式长测期间向Windows申请“系统继续运行”，不修改电源计划，也不要求屏幕保持点亮。
# 请求绑定一个内核句柄：正常/异常退出都会释放；强制结束进程时由操作系统回收句柄。
# 这只阻止自动空闲休眠，用户主动睡眠、合盖策略和重启仍能中断实验，不能掩盖为成功。
if (-not ('DanmakuLab.ExperimentAwake' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
namespace DanmakuLab {
    public sealed class ExperimentAwake : IDisposable {
        // Version=0、Flags=1表示使用一段简单文字作为请求原因，便于powercfg /requests核查。
        [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
        private struct ReasonContext { public uint Version; public uint Flags; public string Reason; }
        [DllImport("kernel32.dll", SetLastError=true)]
        private static extern SafeFileHandle PowerCreateRequest(ref ReasonContext reason);
        [DllImport("kernel32.dll", SetLastError=true)]
        private static extern bool PowerSetRequest(SafeFileHandle handle, int type);
        [DllImport("kernel32.dll", SetLastError=true)]
        private static extern bool PowerClearRequest(SafeFileHandle handle, int type);
        private readonly SafeFileHandle handle;
        public bool Released { get; private set; }
        public ExperimentAwake() {
            var context = new ReasonContext { Version=0, Flags=1, Reason="DanmakuLab bounded T4 experiment" };
            handle = PowerCreateRequest(ref context);
            if (handle.IsInvalid) { handle.Dispose(); throw new Win32Exception(Marshal.GetLastWin32Error()); }
            // 1=PowerRequestSystemRequired；句柄级请求不依赖PowerShell回调运行在哪条线程。
            if (!PowerSetRequest(handle, 1)) {
                var code = Marshal.GetLastWin32Error(); handle.Dispose(); throw new Win32Exception(code);
            }
        }
        public void Dispose() {
            if (Released) return;
            try {
                if (!PowerClearRequest(handle, 1)) throw new Win32Exception(Marshal.GetLastWin32Error());
            } finally { handle.Dispose(); Released=true; }
        }
    }
}
'@
}
function New-ExperimentAwake {
    # 请求失败直接终止编排，不在可能自动休眠的情况下静默继续正式测量。
    [DanmakuLab.ExperimentAwake]::new()
}
Export-ModuleMember -Function New-ExperimentAwake
