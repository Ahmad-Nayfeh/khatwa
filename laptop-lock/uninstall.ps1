<#
.SYNOPSIS
  Removes the khatwa laptop lock: unregisters the logon tasks, stops the processes, and deletes
  %LOCALAPPDATA%\khatwa (use -KeepData to keep config.json and the surrender log).
  Run in an elevated PowerShell (Run as administrator).
#>
[CmdletBinding()]
param([switch]$KeepData)

$ErrorActionPreference = 'Continue'

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Write-Host "شغّل هذا السكربت بصلاحيات المسؤول (Run as administrator)." -ForegroundColor Red; exit 1 }

foreach ($name in 'KhatwaLock', 'KhatwaLockWatchdog') {
  if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "أُزيلت المهمة $name"
  }
}
Get-Process -Name 'KhatwaLock' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

$dir = Join-Path $env:LOCALAPPDATA 'khatwa'
if (Test-Path $dir) {
  if ($KeepData) {
    Remove-Item -Path (Join-Path $dir 'KhatwaLock.exe') -Force -ErrorAction SilentlyContinue
    Write-Host "أُبقي على البيانات في $dir"
  } else {
    Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "حُذف المجلد $dir"
  }
}
Write-Host "تمت الإزالة." -ForegroundColor Green
