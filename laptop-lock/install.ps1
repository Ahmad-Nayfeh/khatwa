<#
.SYNOPSIS
  Installs the khatwa laptop lock: copies KhatwaLock.exe to %LOCALAPPDATA%\khatwa, writes
  config.json with your secret, and registers two logon tasks (lock screen + watchdog) with
  highest privileges. Run in an elevated PowerShell (Run as administrator).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\install.ps1 -Secret "12345678"
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
    $Secret = Read-Host "اكتب كود الاقتران (8 أرقام) من تطبيق خطوة ← الإعدادات ← قفل اللابتوب"
  }
}
$Secret = ($Secret -replace '[^0-9]', '')
if ($Secret.Length -ne 8) { Write-Host "كود الاقتران يجب أن يكون 8 أرقام." -ForegroundColor Red; exit 1 }

$config = @{ secret = $Secret; language = 'ar'; emergencyWaitSeconds = 60 }
if (Test-Path $configPath) {
  try {
    $old = Get-Content $configPath -Raw | ConvertFrom-Json
    if ($old.language) { $config.language = $old.language }
    if ($old.emergencyPhraseAr) { $config.emergencyPhraseAr = $old.emergencyPhraseAr }
    if ($old.emergencyPhraseEn) { $config.emergencyPhraseEn = $old.emergencyPhraseEn }
    if ($old.emergencyWaitSeconds) { $config.emergencyWaitSeconds = [int]$old.emergencyWaitSeconds }
  } catch {}
}
[IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))

$user = "$env:USERDOMAIN\$env:USERNAME"
$principal = New-ScheduledTaskPrincipal -UserId $user -RunLevel Highest -LogonType Interactive
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -Hidden
$trigger   = New-ScheduledTaskTrigger -AtLogOn -User $user

# The GUI (no arguments) is for pairing and entering the lock code; at sign-in only the
# watchdog runs: it shows the lock screen whenever the laptop is locked.
Unregister-ScheduledTask -TaskName 'KhatwaLock' -Confirm:$false -ErrorAction SilentlyContinue
$wdAction = New-ScheduledTaskAction -Execute $target -Argument '--watchdog' -WorkingDirectory $dir
Register-ScheduledTask -TaskName 'KhatwaLockWatchdog' -Action $wdAction -Trigger $trigger -Principal $principal -Settings $settings -Description 'khatwa: show the lock screen whenever the laptop is locked' -Force | Out-Null

Write-Host ""
Write-Host "تم التثبيت في: $dir" -ForegroundColor Green
Write-Host "المهمة المجدولة: KhatwaLockWatchdog (عند تسجيل الدخول، بأعلى صلاحيات)."
Write-Host "لقفل اللابتوب: شغّل $target والصق كود القفل من الجوال. الإزالة: uninstall.ps1"
if (-not $NoStart) {
  Start-ScheduledTask -TaskName 'KhatwaLockWatchdog'
}
