[CmdletBinding()]
param(
    [Parameter(Mandatory)][int]$ProcessId,
    [Parameter(Mandatory)][string]$Scenario,
    [ValidateRange(1, 3600)][int]$Seconds = 60,
    [string]$CsvPath,
    [switch]$RequireSingleNativeProvider
)

$ErrorActionPreference = 'Stop'
if (-not ('CpPlayerMemoryCounters' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class CpPlayerMemoryCounters
{
    [StructLayout(LayoutKind.Sequential)]
    public struct Snapshot
    {
        public uint Size, PageFaultCount;
        public UIntPtr PeakWorkingSet, WorkingSet;
        public UIntPtr PeakPagedPool, PagedPool, PeakNonPagedPool, NonPagedPool;
        public UIntPtr PagefileUsage, PeakPagefileUsage, PrivateCommit, PrivateWorkingSet;
        public ulong SharedCommit;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool K32GetProcessMemoryInfo(IntPtr handle, out Snapshot stats, uint size);

    public static Snapshot Read(IntPtr handle)
    {
        Snapshot stats;
        if (!K32GetProcessMemoryInfo(handle, out stats, (uint)Marshal.SizeOf(typeof(Snapshot))))
            throw new Win32Exception();
        return stats;
    }
}
'@
}
$target = Get-Process -Id $ProcessId
$deadline = [System.Diagnostics.Stopwatch]::StartNew()
$sampleAt = 0
$rows = @(
    while ($deadline.Elapsed.TotalSeconds -lt $Seconds) {
        $target.Refresh()
        if ($target.HasExited) { throw "Process $ProcessId exited during sampling." }
        $counters = [CpPlayerMemoryCounters]::Read($target.Handle)

        # An audio source can ship several DLLs in a single runtime snapshot.
        $snapshots = @(
            $target.Modules | ForEach-Object {
                if ($_.FileName -match '[\\/]([.]repository-runtime-[^\\/]+)[\\/]') { $Matches[1] }
            } | Sort-Object -Unique
        )
        [pscustomobject]@{
            Scenario = $Scenario
            Timestamp = [DateTimeOffset]::Now.ToString('o')
            TotalWsMiB = [Math]::Round($counters.WorkingSet.ToUInt64() / 1MB, 2)
            PrivateWsMiB = [Math]::Round($counters.PrivateWorkingSet.ToUInt64() / 1MB, 2)
            PrivateCommitMiB = [Math]::Round($counters.PrivateCommit.ToUInt64() / 1MB, 2)
            NativeProviderCount = $snapshots.Count
            NativeProviderSnapshots = $snapshots -join ';'
        }
        $sampleAt++
        $waitMs = 1000 * [Math]::Min($sampleAt, $Seconds) - $deadline.ElapsedMilliseconds
        if ($waitMs -gt 0) { Start-Sleep -Milliseconds $waitMs }
    }
)
if ($CsvPath) { $rows | Export-Csv -LiteralPath $CsvPath -NoTypeInformation -Encoding UTF8 }
if (!$rows.Count) { throw 'No samples collected.' }

foreach ($metric in 'TotalWsMiB', 'PrivateWsMiB', 'PrivateCommitMiB', 'NativeProviderCount') {
    $values = @($rows.$metric | Sort-Object)
    $mid = [int][Math]::Floor($values.Count / 2)
    $median = if ($values.Count % 2) { $values[$mid] } else { ($values[$mid - 1] + $values[$mid]) / 2 }
    [pscustomobject]@{ Scenario = $Scenario; Metric = $metric; Samples = $values.Count; Median = $median; Peak = $values[-1] }
}
if ($RequireSingleNativeProvider -and ($rows | Where-Object { $_.NativeProviderCount -gt 1 })) {
    throw 'More than one native provider snapshot was loaded in at least one sample.'
}
