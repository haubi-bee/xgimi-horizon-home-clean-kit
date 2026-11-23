<#
.SYNOPSIS
    Sets up an XGIMI Horizon Pro (XK03H, Android TV 11): disables bloat and telemetry packages,
    installs the Clean Home launcher and makes it the home screen and photo screensaver.

.DESCRIPTION
    Everything is reversible: packages are disabled (never uninstalled), and every value this script
    changes is written, together with its previous value, to backup-<timestamp>\state.json next to
    this script. .\restore.ps1 uses that file to undo the changes exactly.

    Do not run this while someone is watching or playing: installing can show a Play Protect dialog
    and the script presses HOME on the device.

    Requirements: adb on this PC (winget install Google.PlatformTools) and network debugging enabled
    on the projector (Settings > Device Preferences > About > press OK on "Build" 7 times, then
    Developer options > Network debugging / USB debugging). Confirm the "Allow debugging?" prompt on
    the projector screen the first time.

.PARAMETER Ip
    IPv4 address of the projector. Remembered in %LOCALAPPDATA%\CleanHomeKit\config.json, so later
    runs can omit it. Use .\find-projector.ps1 to find it.

.PARAMETER Apk
    Launcher APK to install. Default: CleanHome.apk next to this script, else the signed release
    build, else the debug build from ..\launcher\app\build\outputs\apk.

.PARAMETER SkipDebloat
    Do not disable any packages.

.PARAMETER KeepAssistant
    Keep the Google Assistant / voice search (com.google.android.katniss) enabled.

.PARAMETER FullPowerOff
    Make the power key shut the projector down instead of entering standby.

.PARAMETER SecureDns
    quad9: use encrypted DNS via dns.quad9.net (rolled back automatically if name resolution fails).
    off (default): leave DNS untouched.

.PARAMETER WeatherCity
    Optional city for the weather next to the clock (resolved on the TV via Open-Meteo).

.PARAMETER Force
    Run on models other than the XK03H (only warns instead of stopping). Untested.

.EXAMPLE
    .\setup.ps1 -Ip 192.168.1.50

.EXAMPLE
    .\setup.ps1 -Ip 192.168.1.50 -KeepAssistant -SecureDns quad9 -WeatherCity "Hamburg"
#>
[CmdletBinding()]
param(
    [string]$Ip,
    [string]$Apk,
    [switch]$SkipDebloat,
    [switch]$KeepAssistant,
    [switch]$FullPowerOff,
    [ValidateSet('quad9', 'off')][string]$SecureDns = 'off',
    [string]$WeatherCity,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$LauncherPackage = 'tv.cleanhome.launcher'
$HomeComponent = "$LauncherPackage/.HomeActivity"
$DreamComponent = "$LauncherPackage/.PhotoDreamService"
$GoogleLauncher = 'com.google.android.tvlauncher'
$FileManager = 'com.xgimi.filemanager'
$PhotoDir = '/sdcard/Pictures/CleanHome'
$SupportedModel = 'XK03H'
$KitDir = Join-Path $env:LOCALAPPDATA 'CleanHomeKit'
$ConfigPath = Join-Path $KitDir 'config.json'

# Disabled with "pm disable-user --user 0" (reversible with "pm enable"), only if installed.
$BloatPackages = @(
    'com.xgimi.bugreportsender',            # XGIMI bug report uploader
    'com.xgimi.doubanfm',                   # Douban FM music app (China)
    'fusion.android.tv.demo',               # retail demo mode
    'com.google.android.feedback',          # Google feedback / crash reports
    'com.google.android.tvrecommendations', # recommendation service of the Google TV home screen
    'com.google.android.play.games',        # Google Play Games
    'com.google.android.videos',            # Google TV / Play Movies
    'com.ted.android.tv',                   # TED
    'com.android.printspooler'              # print service (no printers on a projector)
)
$AssistantPackage = 'com.google.android.katniss' # Google Assistant / voice search (microphone)

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

function Save-State {
    $script:State | ConvertTo-Json -Depth 6 | Out-File $script:StatePath -Encoding utf8
}

function Get-Setting([string]$Namespace, [string]$Key) {
    $value = Get-ShellText "settings get $Namespace $Key"
    if ($value -eq '' -or $value -eq 'null') { return $null }
    return $value
}

function Set-DeviceSetting([string]$Namespace, [string]$Key, [AllowNull()][string]$Value) {
    if ($null -eq $Value -or $Value -eq '') {
        Invoke-Shell "settings delete $Namespace $Key" | Out-Null
    } else {
        Invoke-Shell "settings put $Namespace $Key $(ConvertTo-ShellArgument $Value)" | Out-Null
    }
}

# Changes a setting and records the previous value in state.json (first previous value wins).
function Set-TrackedSetting([string]$Namespace, [string]$Key, [string]$Value) {
    $previous = Get-Setting $Namespace $Key
    Set-DeviceSetting $Namespace $Key $Value
    $entry = $script:State.settings | Where-Object { $_.namespace -eq $Namespace -and $_.key -eq $Key } | Select-Object -First 1
    if ($entry) {
        $entry.value = $Value
    } else {
        $script:State.settings.Add([ordered]@{ namespace = $Namespace; key = $Key; previous = $previous; value = $Value }) | Out-Null
    }
    Save-State
    Write-Host "  $Namespace $Key = $Value (was: $(if ($null -eq $previous) { 'unset' } else { $previous }))"
}

function Undo-TrackedSetting([string]$Namespace, [string]$Key) {
    $entry = $script:State.settings | Where-Object { $_.namespace -eq $Namespace -and $_.key -eq $Key } | Select-Object -First 1
    if (-not $entry) { return }
    Set-DeviceSetting $Namespace $Key $entry.previous
    $script:State.settings.Remove($entry) | Out-Null
    Save-State
}

function Get-ResumedActivity {
    return Get-ShellText "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 1"
}

function Resolve-Apk([string]$Path) {
    if ($Path) {
        if (-not (Test-Path $Path)) { throw "APK not found: $Path" }
        return (Resolve-Path $Path).Path
    }
    $candidates = @(
        (Join-Path $PSScriptRoot 'CleanHome.apk'),
        (Join-Path $PSScriptRoot '..\launcher\app\build\outputs\apk\release\app-release.apk'),
        (Join-Path $PSScriptRoot '..\launcher\app\build\outputs\apk\debug\app-debug.apk')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    }
    throw "No launcher APK found. Build it (see README) or pass -Apk <path>. Looked for: $($candidates -join ', ')"
}

# ---------------------------------------------------------------- start

Write-Host ''
Write-Host 'WARNING: do not run this while someone is watching or playing on the projector.' -ForegroundColor Yellow
Write-Host '         Installing can show a Play Protect dialog, and the script presses HOME on the device.' -ForegroundColor Yellow

$script:Adb = Find-Adb
$Apk = Resolve-Apk $Apk
$Ip = Resolve-DeviceIp $Ip

Write-Step "Connecting to $Ip"
Connect-Device $Ip
$model = Get-ShellText 'getprop ro.product.model'
$release = Get-ShellText 'getprop ro.build.version.release'
$build = Get-ShellText 'getprop ro.build.display.id'
$deviceSerial = Get-ShellText 'getprop ro.serialno'
Write-Host "  Connected: $model, Android $release, build $build"
if ($model -ne $SupportedModel) {
    if (-not $Force) {
        throw "This script is only tested on the XGIMI Horizon Pro ($SupportedModel); found '$model'. Nothing was changed. Use -Force to run anyway (untested)."
    }
    Write-Warning "Model '$model' is not the tested $SupportedModel. Continuing because of -Force."
}

# ---------------------------------------------------------------- 1) backup

Write-Step 'Backing up the current state'
$backupDir = Join-Path $PSScriptRoot ('backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $backupDir | Out-Null
(Invoke-Shell 'pm list packages -f -s') | Out-File (Join-Path $backupDir 'packages-system.txt') -Encoding utf8
(Invoke-Shell 'pm list packages -f -3') | Out-File (Join-Path $backupDir 'packages-user.txt') -Encoding utf8
(Invoke-Shell 'pm list packages -d') | Out-File (Join-Path $backupDir 'packages-disabled.txt') -Encoding utf8
foreach ($namespace in 'global', 'secure', 'system') {
    (Invoke-Shell "settings list $namespace") | Out-File (Join-Path $backupDir "settings-$namespace.txt") -Encoding utf8
}

$installed = Get-PackageSet ''
$disabled = Get-PackageSet '-d'
$script:StatePath = Join-Path $backupDir 'state.json'
$script:State = [ordered]@{
    formatVersion          = 1
    createdUtc             = (Get-Date).ToUniversalTime().ToString('o')
    device                 = [ordered]@{ serial = $deviceSerial; model = $model; androidRelease = $release; build = $build }
    disabledPackages       = New-Object System.Collections.ArrayList
    settings               = New-Object System.Collections.ArrayList
    appops                 = New-Object System.Collections.ArrayList
    launcherWasInstalled   = $installed.ContainsKey($LauncherPackage)
    launcherInstalled      = $false
    googleLauncherDisabled = $false
}
Save-State
Write-Host "  Backup: $backupDir"

# ---------------------------------------------------------------- 2) debloat

if ($SkipDebloat) {
    Write-Step 'Skipping package changes (-SkipDebloat)'
} else {
    Write-Step 'Disabling bloat and telemetry packages (reversible)'
    $toDisable = @($BloatPackages)
    if (-not $KeepAssistant) { $toDisable += $AssistantPackage }
    foreach ($package in $toDisable) {
        if (-not $installed.ContainsKey($package)) { continue }
        if ($disabled.ContainsKey($package)) {
            Write-Host "  $package already disabled"
            continue
        }
        $result = (Invoke-Shell "pm disable-user --user 0 $package" -AllowFailure) -join ' '
        if ($result -match 'disabled') {
            $script:State.disabledPackages.Add($package) | Out-Null
            Save-State
            Write-Host "  $package disabled"
        } else {
            Write-Warning "$package could not be disabled: $result"
        }
    }
}

Write-Step 'Faster animations, no crash report dialogs'
Set-TrackedSetting 'global' 'window_animation_scale' '0.5'
Set-TrackedSetting 'global' 'transition_animation_scale' '0.5'
Set-TrackedSetting 'global' 'animator_duration_scale' '0.5'
Set-TrackedSetting 'global' 'send_action_app_error' '0'

# ---------------------------------------------------------------- 3) install the launcher

Write-Step "Installing $Apk"
$install = (Invoke-Adb -Arguments @('-s', $script:Serial, 'install', '-r', $Apk) -AllowFailure) -join ' '
if ($install -notmatch 'Success') { throw "Installation failed: $install" }
$script:State.launcherInstalled = $true
Save-State
Invoke-Shell "pm grant $LauncherPackage android.permission.READ_EXTERNAL_STORAGE" | Out-Null
Invoke-Shell "pm grant $LauncherPackage android.permission.READ_TV_LISTINGS" | Out-Null
Invoke-Shell "mkdir -p $PhotoDir" | Out-Null
Write-Host "  Installed, permissions granted, photo folder $PhotoDir ready."

# ---------------------------------------------------------------- 4) home screen

Write-Step 'Making Clean Home the home screen'
# The Google launcher has a higher HOME priority while it is enabled, so it is only disabled once
# Clean Home has been proven to start.
Invoke-Shell "am start -n $HomeComponent" | Out-Null
$running = $false
for ($i = 0; $i -lt 10 -and -not $running; $i++) {
    Start-Sleep -Seconds 1
    $running = [bool](Get-ShellText "pidof $LauncherPackage")
}
if (-not $running) {
    throw 'Clean Home does not start. The Google launcher stays active; run .\restore.ps1 to undo the other changes.'
}
$homeResult = (Invoke-Shell "cmd package set-home-activity $HomeComponent" -AllowFailure) -join ' '
Write-Host "  set-home-activity: $homeResult"
$installed = Get-PackageSet ''
$disabled = Get-PackageSet '-d'
if ($installed.ContainsKey($GoogleLauncher) -and -not $disabled.ContainsKey($GoogleLauncher)) {
    Invoke-Shell "pm disable-user --user 0 $GoogleLauncher" | Out-Null
    $script:State.googleLauncherDisabled = $true
    Save-State
    Write-Host "  $GoogleLauncher disabled"
}
Start-Sleep -Seconds 3
Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
Start-Sleep -Seconds 5
$resumed = Get-ResumedActivity
if ($resumed -notmatch [regex]::Escape($LauncherPackage)) {
    if ($script:State.googleLauncherDisabled) {
        Invoke-Shell "pm enable $GoogleLauncher" | Out-Null
        $script:State.googleLauncherDisabled = $false
        Save-State
    }
    Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
    throw "HOME does not open Clean Home ($resumed). The Google launcher was re-enabled; run .\restore.ps1 to undo the other changes."
}
Write-Host '  HOME opens Clean Home.'

# ---------------------------------------------------------------- 5) screensaver

Write-Step 'Photo screensaver'
Set-TrackedSetting 'secure' 'screensaver_components' $DreamComponent

# ---------------------------------------------------------------- 6) optional power key

if ($FullPowerOff) {
    Write-Step 'Power key: full shutdown instead of standby'
    Set-TrackedSetting 'system' 'power_key_definition' '1'
}

# ---------------------------------------------------------------- 7) optional encrypted DNS

if ($SecureDns -eq 'quad9') {
    Write-Step 'Encrypted DNS (dns.quad9.net)'
    Set-TrackedSetting 'global' 'private_dns_specifier' 'dns.quad9.net'
    Set-TrackedSetting 'global' 'private_dns_mode' 'hostname'
    $resolved = $false
    for ($i = 0; $i -lt 4 -and -not $resolved; $i++) {
        Start-Sleep -Seconds 3
        # "PING host (address)" means the name was resolved, even if ICMP itself is blocked.
        $ping = Get-ShellText 'ping -c 1 -W 3 example.com 2>&1'
        $resolved = $ping -match 'PING example\.com \('
    }
    if ($resolved) {
        Write-Host '  Name resolution works.'
    } else {
        Undo-TrackedSetting 'global' 'private_dns_mode'
        Undo-TrackedSetting 'global' 'private_dns_specifier'
        Write-Warning 'Name resolution failed with encrypted DNS; the previous DNS settings were restored.'
    }
}

# ---------------------------------------------------------------- 8) no sideloading from the file manager

if ($installed.ContainsKey($FileManager)) {
    Write-Step 'Revoking APK installs from the XGIMI file manager'
    $current = Get-ShellText "appops get $FileManager REQUEST_INSTALL_PACKAGES"
    $previousMode = 'default'
    if ($current -match 'REQUEST_INSTALL_PACKAGES:\s*(\w+)') { $previousMode = $Matches[1] }
    Invoke-Shell "appops set $FileManager REQUEST_INSTALL_PACKAGES deny" | Out-Null
    $script:State.appops.Add([ordered]@{ package = $FileManager; op = 'REQUEST_INSTALL_PACKAGES'; previous = $previousMode; value = 'deny' }) | Out-Null
    Save-State
    Write-Host "  REQUEST_INSTALL_PACKAGES = deny (was: $previousMode)"
}

# ---------------------------------------------------------------- 9) optional weather location

if ($PSBoundParameters.ContainsKey('WeatherCity')) {
    Write-Step "Weather location: $WeatherCity"
    Invoke-Shell "am start -n $HomeComponent --es weather_city $(ConvertTo-ShellArgument $WeatherCity.Trim())" | Out-Null
    Write-Host '  Sent to the launcher; it looks the place up itself (a message appears on the TV).'
}

Write-Host ''
Write-Host 'Done. Clean Home is the home screen.' -ForegroundColor Green
Write-Host "  Changes recorded in: $($script:StatePath)"
Write-Host "  Copy photos:          .\sync-photos.ps1 -Folder `"<folder with photos>`""
Write-Host '  Undo everything:      .\restore.ps1'
Write-Host ''
Write-Host 'IMPORTANT: turn network debugging off again on the projector (Developer options).' -ForegroundColor Yellow
Write-Host '           While it is on, anyone on your network can control the device.' -ForegroundColor Yellow
