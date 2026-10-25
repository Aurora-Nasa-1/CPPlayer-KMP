# Click inside the CPPlayer window at a RELATIVE position (0..1 of width/height), then capture.
# Usage: app-click.ps1 -Fx 0.02 -Fy 0.17 -Out "E:\path\01-home.png"
# Keep this file ASCII-only: Windows PowerShell 5.1 reads .ps1 as ANSI without a BOM.
param(
    [double]$Fx = -1,
    [double]$Fy = -1,
    [string]$Out = "",
    [int]$WaitMs = 900
)

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class ClickApi {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

$proc = Get-Process | Where-Object { $_.MainWindowTitle -like "*CPPlayer*" } | Select-Object -First 1
if (-not $proc) { Write-Error "CPPlayer window not found"; exit 1 }
$h = $proc.MainWindowHandle
[ClickApi]::ShowWindow($h, 9) | Out-Null
[ClickApi]::SetForegroundWindow($h) | Out-Null
Start-Sleep -Milliseconds 300

$r = New-Object ClickApi+RECT
[ClickApi]::GetWindowRect($h, [ref]$r) | Out-Null
$w = $r.Right - $r.Left
$ht = $r.Bottom - $r.Top

$x = -1
$y = -1
if ($Fx -ge 0 -and $Fy -ge 0) {
    $x = [int]($r.Left + $w * $Fx)
    $y = [int]($r.Top + $ht * $Fy)
    [ClickApi]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 120
    [ClickApi]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [ClickApi]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds $WaitMs
}

if ($Out -ne "") {
    $bmp = New-Object System.Drawing.Bitmap $w, $ht
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
    $dir = Split-Path -Parent $Out
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Output "OK $Out click=($Fx,$Fy) screen=($x,$y) win=${w}x${ht}"
} else {
    Write-Output "OK click=($Fx,$Fy) screen=($x,$y)"
}
