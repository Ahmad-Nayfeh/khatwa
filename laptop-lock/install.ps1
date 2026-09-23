<#
.SYNOPSIS
  Installs the khatwa laptop lock: copies KhatwaLock.exe to %LOCALAPPDATA%\khatwa, writes
  config.json with your secret, and registers two logon tasks (lock screen + watchdog) with
  highest privileges. Run in an elevated PowerShell (Run as administrator).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\install.ps1 -Secret "K7mP2qR9sT4vW6xZ3bN8cD5f"
  powershell -ExecutionPolicy Bypass -File .\install.ps1            # prompts for the secret
#>
[CmdletBinding()]
param(
  [string]$Secret,
  [string]$ExePath,
  [switch]$NoStart
)

$ErrorActionPreference = 'Stop'

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  Write-Host "شغّل هذا السكربت بصلاحيات المسؤول (Run as administrator)." -ForegroundColor Red
  exit 1
}

$dir = Join-Path $env:LOCALAPPDATA 'khatwa'
New-Item -ItemType Directory -Force -Path $dir | Out-Null

if (-not $ExePath) {
  $candidate = Get-ChildItem -Path $PSScriptRoot -Filter 'khatwa-laptop-lock*.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $candidate) { $candidate = Get-ChildItem -Path $PSScriptRoot -Filter 'KhatwaLock.exe' -ErrorAction SilentlyContinue | Select-Object -First 1 }
  if (-not $candidate) { Write-Host "ضع ملف khatwa-laptop-lock-<version>.exe في نفس مجلد السكربت أو مرّر -ExePath." -ForegroundColor Red; exit 1 }
  $ExePath = $candidate.FullName
}
$target = Join-Path $dir 'KhatwaLock.exe'

# Stop running instances before overwriting the exe.
Get-Process -Name 'KhatwaLock' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Copy-Item -Path $ExePath -Destination $target -Force
Unblock-File -Path $target -ErrorAction SilentlyContinue

$configPath = Join-Path $dir 'config.json'
if (-not $Secret) {
  if (Test-Path $configPath) {
    try { $existing = (Get-Content $configPath -Raw | ConvertFrom-Json).secret } catch { $existing = $null }
  }
  if ($existing) {
    $Secret = $existing
    Write-Host "استُخدم السر الموجود في config.json."
  } else {
    $Secret = Read-Host "الصق السر (24 حرفاً) من تطبيق خطوة ← الإعدادات ← قفل اللابتوب"
  }
}
$Secret = $Secret.Trim()
if ($Secret.Length -lt 8) { Write-Host "السر قصير جداً." -ForegroundColor Red; exit 1 }

$config = @{ secret = $Secret; emergencyPhrase = 'أختار الاستسلام اليوم وأعلم أن هذا يُسجَّل'; emergencyWaitSeconds = 60 }
if (Test-Path $configPath) {
  try {
    $old = Get-Content $configPath -Raw | ConvertFrom-Json
    if ($old.emergencyPhrase) { $config.emergencyPhrase = $old.emergencyPhrase }
    if ($old.emergencyWaitSeconds) { $config.emergencyWaitSeconds = [int]$old.emergencyWaitSeconds }
  } catch {}
}
[IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))

$user = "$env:USERDOMAIN\$env:USERNAME"
$principal = New-ScheduledTaskPrincipal -UserId $user -RunLevel Highest -LogonType Interactive
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -Hidden
$trigger   = New-ScheduledTaskTrigger -AtLogOn -User $user

$lockAction = New-ScheduledTaskAction -Execute $target -WorkingDirectory $dir
Register-ScheduledTask -TaskName 'KhatwaLock' -Action $lockAction -Trigger $trigger -Principal $principal -Settings $settings -Description 'khatwa: lock the laptop until the daily walking code is entered' -Force | Out-Null

$wdAction = New-ScheduledTaskAction -Execute $target -Argument '--watchdog' -WorkingDirectory $dir
Register-ScheduledTask -TaskName 'KhatwaLockWatchdog' -Action $wdAction -Trigger $trigger -Principal $principal -Settings $settings -Description 'khatwa: restart the lock screen if it is closed while still locked' -Force | Out-Null

Write-Host ""
Write-Host "تم التثبيت في: $dir" -ForegroundColor Green
Write-Host "المهمتان المجدولتان: KhatwaLock و KhatwaLockWatchdog (عند تسجيل الدخول، بأعلى صلاحيات)."
Write-Host "الإزالة: uninstall.ps1"
if (-not $NoStart) {
  Write-Host "تشغيل تجريبي الآن... (أدخل كود اليوم من التطبيق، أو استخدم الطوارئ)"
  Start-ScheduledTask -TaskName 'KhatwaLockWatchdog'
  Start-ScheduledTask -TaskName 'KhatwaLock'
}
