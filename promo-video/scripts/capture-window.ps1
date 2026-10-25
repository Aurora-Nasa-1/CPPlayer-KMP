# 截取 CPPlayer 窗口（按窗口矩形，不是整屏）
# 用法：powershell -ExecutionPolicy Bypass -File capture-window.ps1 -Out "E:\...\shot.png"
param(
    [Parameter(Mandatory = $true)][string]$Out,
    [int]$ProcessId = 0,
    [switch]$NoFocus,
    [switch]$FullScreen
)

Add-Type -AssemblyName System.Drawing

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WinApi {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint cButtons, IntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

if ($ProcessId -eq 0) {
    $proc = Get-Process | Where-Object { $_.MainWindowTitle -like "*CPPlayer*" } | Select-Object -First 1
} else {
    $proc = Get-Process -Id $ProcessId
}
if (-not $proc) { Write-Error "找不到 CPPlayer 窗口"; exit 1 }

$h = $proc.MainWindowHandle
if ($h -eq [IntPtr]::Zero) { Write-Error "窗口句柄为空"; exit 2 }

if (-not $NoFocus) {
    [WinApi]::ShowWindow($h, 9) | Out-Null   # SW_RESTORE
    [WinApi]::SetForegroundWindow($h) | Out-Null
    Start-Sleep -Milliseconds 350
}

if ($FullScreen) {
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $left = 0; $top = 0; $w = $b.Width; $ht = $b.Height
} else {
    $r = New-Object WinApi+RECT
    [WinApi]::GetWindowRect($h, [ref]$r) | Out-Null
    $left = $r.Left; $top = $r.Top
    $w = $r.Right - $r.Left; $ht = $r.Bottom - $r.Top
}

$bmp = New-Object System.Drawing.Bitmap $w, $ht
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($left, $top, 0, 0, $bmp.Size)
$dir = Split-Path -Parent $Out
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

Write-Output "OK $Out rect=$left,$top ${w}x${ht} title=$($proc.MainWindowTitle)"
