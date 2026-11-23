<#
.SYNOPSIS
    Undoes what setup.ps1 changed on the projector and removes the Clean Home launcher.

.DESCRIPTION
    Uses the state.json files that setup.ps1 writes into backup-<timestamp>\ next to the scripts.
    Without -BackupDir, every recorded setup run for the connected device is undone, newest first,
    so the oldest recorded values (the original state) win. If no state.json exists, sane factory
    defaults are restored instead (see docs\what-gets-changed.md).

    The Google launcher is re-enabled first, so HOME always has a target. Photos in
    /sdcard/Pictures/CleanHome stay on the device.

.PARAMETER Ip
    IPv4 address of the projector. Defaults to the address remembered by the other scripts.

.PARAMETER BackupDir
    Undo only the run recorded in this backup folder.

.EXAMPLE
    .\restore.ps1 -Ip 192.168.1.50
#>
[CmdletBinding()]
param(
    [string]$Ip,
    [string]$BackupDir
)

$ErrorActionPreference = 'Stop'

$LauncherPackage = 'tv.cleanhome.launcher'
$GoogleLauncher = 'com.google.android.tvlauncher'
$FileManager = 'com.xgimi.filemanager'
$DefaultScreensaver = 'com.google.android.backdrop/.Backdrop'
$KitDir = Join-Path $env:LOCALAPPDATA 'CleanHomeKit'
$ConfigPath = Join-Path $KitDir 'config.json'

# Everything setup.ps1 may disable; used when no state.json is available.
$DefaultPackages = @(
    'com.google.android.tvrecommendations',
    'com.google.android.katniss',
    'com.google.android.feedback',
    'com.google.android.play.games',
    'com.google.android.videos',
    'com.ted.android.tv',
    'com.android.printspooler',
    'com.xgimi.bugreportsender',
    'com.xgimi.doubanfm',
    'fusion.android.tv.demo'
)

# ---------------------------------------------------------------- helpers

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host "== $Text" -ForegroundColor Cyan
}

function Find-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $wingetDir = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    if (Test-Path $wingetDir) {
        $found = Get-ChildItem $wingetDir -Recurse -Filter adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    throw 'adb not found. Install it with "winget install Google.PlatformTools" and open a new PowerShell window.'
}

function Get-KitConfig {
    $config = [ordered]@{ ip = $null; photoFolder = $null }
    if (Test-Path $ConfigPath) {
        try {
            $saved = Get-Content $ConfigPath -Raw | ConvertFrom-Json
            foreach ($name in @('ip', 'photoFolder')) {
                if ($saved.PSObject.Properties[$name] -and $saved.$name) { $config[$name] = [string]$saved.$name }
            }
        } catch {
            Write-Warning "Ignoring unreadable config file $ConfigPath"
        }
    }
    return $config
}

function Save-KitConfig($Config) {
    New-Item -ItemType Directory -Force $KitDir | Out-Null
    [pscustomobject]$Config | ConvertTo-Json | Out-File $ConfigPath -Encoding utf8
}

function Resolve-DeviceIp([string]$Value) {
    $config = Get-KitConfig
    if (-not $Value) { $Value = $config.ip }
    if (-not $Value) { throw 'No projector address known. Pass -Ip <address> (find it with .\find-projector.ps1).' }
    if ($Value -notmatch '^\d{1,3}(\.\d{1,3}){3}$' -or -not ($Value -as [System.Net.IPAddress])) {
        throw "Not an IPv4 address: $Value"
    }
    $config.ip = $Value
    Save-KitConfig $config
    return $Value
}

# Runs adb. With $ErrorActionPreference = 'Stop', PowerShell 5.1 turns redirected stderr of a native
# command into a terminating error, so stderr is captured under a local 'Continue' and success is
# decided by the exit code.
function Invoke-Adb([string[]]$Arguments, [switch]$AllowFailure) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $script:Adb @Arguments 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed (exit code $exitCode): $($output -join ' ')"
    }
    return $output
}

function Invoke-Shell([string]$Command, [switch]$AllowFailure) {
    return Invoke-Adb -Arguments @('-s', $script:Serial, 'shell', $Command) -AllowFailure:$AllowFailure
}

function Get-ShellText([string]$Command) {
    return ((Invoke-Shell $Command -AllowFailure) -join "`n").Trim()
}

function ConvertTo-ShellArgument([string]$Value) {
    return "'" + ($Value -replace "'", "'\''") + "'"
}

function Connect-Device([string]$Address) {
    $script:Serial = "${Address}:5555"
    Invoke-Adb -Arguments @('connect', $script:Serial) -AllowFailure | Out-Null
    $state = ((Invoke-Adb -Arguments @('-s', $script:Serial, 'get-state') -AllowFailure) -join ' ').Trim()
    if ($state -ne 'device') {
        throw "Projector at $($script:Serial) is not ready ($state). Is network debugging on, and was the 'Allow debugging?' prompt on the projector confirmed?"
    }
}

function Get-PackageSet([string]$Flags) {
    $set = @{}
    foreach ($line in (Invoke-Shell "pm list packages $Flags")) {
        $name = ($line -replace '^package:', '').Trim()
        if ($name) { $set[$name] = $true }
    }
    return $set
}

function Enable-Package([string]$Package) {
    if (-not $script:Installed.ContainsKey($Package)) { return }
    $result = (Invoke-Shell "pm enable $Package" -AllowFailure) -join ' '
    Write-Host "  $Package -> $result"
}

function Set-DeviceSetting([string]$Namespace, [string]$Key, [AllowNull()][string]$Value) {
    if ($null -eq $Value -or $Value -eq '') {
        Invoke-Shell "settings delete $Namespace $Key" -AllowFailure | Out-Null
        Write-Host "  $Namespace $Key -> deleted (system default)"
    } else {
        Invoke-Shell "settings put $Namespace $Key $(ConvertTo-ShellArgument $Value)" -AllowFailure | Out-Null
        Write-Host "  $Namespace $Key -> $Value"
    }
}

function Set-AppOp([string]$Package, [string]$Op, [string]$Mode) {
    if (-not $script:Installed.ContainsKey($Package)) { return }
    Invoke-Shell "appops set $Package $Op $Mode" -AllowFailure | Out-Null
    Write-Host "  $Package $Op -> $Mode"
}

function Get-StateFiles([string]$DeviceSerial) {
    if ($BackupDir) {
        $path = Join-Path $BackupDir 'state.json'
        if (Test-Path $path) { return @((Resolve-Path $path).Path) }
        Write-Warning "No state.json in $BackupDir; restoring defaults instead."
        return @()
    }
    $files = @(Get-ChildItem $PSScriptRoot -Directory -Filter 'backup-*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'state.json' } |
            Where-Object { Test-Path $_ })
    $matching = @()
    foreach ($file in $files) {
        $state = Get-Content $file -Raw | ConvertFrom-Json
        if ($state.device.serial -and $DeviceSerial -and $state.device.serial -ne $DeviceSerial) {
            Write-Host "  Skipping $file (recorded for another device)"
            continue
        }
        $matching += $file
    }
    return $matching
}

function Restore-FromState([string]$Path) {
    Write-Step "Undoing $Path"
    $state = Get-Content $Path -Raw | ConvertFrom-Json
    if ($state.googleLauncherDisabled) { Enable-Package $GoogleLauncher }
    foreach ($package in @($state.disabledPackages)) {
        if ($package) { Enable-Package $package }
    }
    $settings = @($state.settings)
    for ($i = $settings.Count - 1; $i -ge 0; $i--) {
        $entry = $settings[$i]
        if ($entry) { Set-DeviceSetting $entry.namespace $entry.key $entry.previous }
    }
    foreach ($op in @($state.appops)) {
        if ($op) { Set-AppOp $op.package $op.op $op.previous }
    }
}

function Restore-Defaults {
    Write-Step 'No recorded setup found: restoring factory defaults'
    foreach ($package in $DefaultPackages) { Enable-Package $package }
    Set-DeviceSetting 'global' 'window_animation_scale' '1.0'
    Set-DeviceSetting 'global' 'transition_animation_scale' '1.0'
    Set-DeviceSetting 'global' 'animator_duration_scale' '1.0'
    Set-DeviceSetting 'global' 'send_action_app_error' '1'
    Set-DeviceSetting 'global' 'private_dns_specifier' $null
    Set-DeviceSetting 'global' 'private_dns_mode' $null
    Set-DeviceSetting 'system' 'power_key_definition' $null
    Set-DeviceSetting 'secure' 'screensaver_components' $DefaultScreensaver
    Set-AppOp $FileManager 'REQUEST_INSTALL_PACKAGES' 'allow'
}

# ---------------------------------------------------------------- start

$script:Adb = Find-Adb
$Ip = Resolve-DeviceIp $Ip

Write-Step "Connecting to $Ip"
Connect-Device $Ip
$deviceSerial = Get-ShellText 'getprop ro.serialno'
$script:Installed = Get-PackageSet ''

Write-Step 'Re-enabling the Google launcher first'
Enable-Package $GoogleLauncher

$stateFiles = @(Get-StateFiles $deviceSerial)
if ($stateFiles.Count -eq 0) {
    Restore-Defaults
} else {
    foreach ($file in $stateFiles) { Restore-FromState $file }
}

Write-Step 'Removing Clean Home (photos in /sdcard/Pictures/CleanHome stay on the device)'
if ($script:Installed.ContainsKey($LauncherPackage)) {
    $result = (Invoke-Adb -Arguments @('-s', $script:Serial, 'uninstall', $LauncherPackage) -AllowFailure) -join ' '
    Write-Host "  uninstall: $result"
} else {
    Write-Host '  Not installed.'
}

Invoke-Shell 'input keyevent KEYCODE_HOME' -AllowFailure | Out-Null

Write-Host ''
Write-Host 'Done. The projector is back to its previous home screen and settings.' -ForegroundColor Green
Write-Host 'IMPORTANT: turn network debugging off again on the projector (Developer options).' -ForegroundColor Yellow
