<#
.SYNOPSIS
    [DEPRECATED] AppCDS generator - Replaced by Project Leyden AOT Cache (JEP 483 / 514).
#>

[CmdletBinding()]
param(
    [string]$OutputFile = ""
)

Write-Warning "AppCDS 已被 Project Leyden Ahead-of-Time (AOT) Cache (JEP 483 / 514) 完全替代。"
Write-Host "正在自动调用 generate-aot-cache.ps1 进行 AOT 预编译与类加载链接固化..." -ForegroundColor Cyan

$aotScript = Join-Path $PSScriptRoot "generate-aot-cache.ps1"
if ($OutputFile) {
    & $aotScript -OutputFile $OutputFile
} else {
    & $aotScript
}
