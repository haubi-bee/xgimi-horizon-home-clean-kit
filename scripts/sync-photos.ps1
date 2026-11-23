<#
.SYNOPSIS
    Copies a photo folder from this PC to the projector (wallpaper + screensaver of Clean Home).

.DESCRIPTION
    Photos are resized first (long edge at most 2560 px, JPEG, EXIF orientation applied) so the
    low-memory device stays smooth. The device folder /sdcard/Pictures/CleanHome is mirrored:
    photos removed from the PC folder are removed from the device as well.

    Folder and address are remembered in %LOCALAPPDATA%\CleanHomeKit\config.json; converted photos
    are cached in %LOCALAPPDATA%\CleanHomeKit\photo-cache.

.PARAMETER Folder
    Photo folder on this PC (searched recursively). Default: the remembered folder, else
    Pictures\CleanHome (created if missing).

.PARAMETER Ip
    IPv4 address of the projector. Default: the remembered address.

.EXAMPLE
    .\sync-photos.ps1 -Folder "D:\Photos\Wallpaper" -Ip 192.168.1.50

.EXAMPLE
    .\sync-photos.ps1
#>
[CmdletBinding()]
param(
    [string]$Folder,
    [string]$Ip
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$KitDir = Join-Path $env:LOCALAPPDATA 'CleanHomeKit'
$ConfigPath = Join-Path $KitDir 'config.json'
$CacheDir = Join-Path $KitDir 'photo-cache'
$RemoteDir = '/sdcard/Pictures/CleanHome'
$MaxEdge = 2560
$JpegQuality = 88

# ---------------------------------------------------------------- helpers

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

# Runs adb. With $ErrorActionPreference = 'Stop', PowerShell 5.1 turns redirected stderr of a native
# command (adb push writes its progress there) into a terminating error, so stderr is captured
# under a local 'Continue' and success is decided by the exit code.
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

# Stable file name per source photo: changes when the file is replaced or edited.
function Get-StableName([System.IO.FileInfo]$File, [string]$Root) {
    $relative = $File.FullName.Substring($Root.Length).TrimStart('\')
    $key = "$relative|$($File.Length)|$($File.LastWriteTimeUtc.Ticks)"
    $sha = [System.Security.Cryptography.SHA1]::Create()
    try {
        $hash = ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($key)) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $sha.Dispose()
    }
    return $hash.Substring(0, 16) + '.jpg'
}

function Convert-Photo([string]$Source, [string]$Target) {
    $image = [System.Drawing.Image]::FromFile($Source)
    try {
        # Apply the EXIF orientation (tag 0x0112) so portrait photos are upright.
        if ($image.PropertyIdList -contains 0x0112) {
            $flip = switch ([int]$image.GetPropertyItem(0x0112).Value[0]) {
                2 { 'RotateNoneFlipX' } 3 { 'Rotate180FlipNone' } 4 { 'Rotate180FlipX' }
                5 { 'Rotate90FlipX' } 6 { 'Rotate90FlipNone' } 7 { 'Rotate270FlipX' } 8 { 'Rotate270FlipNone' }
                default { $null }
            }
            if ($flip) { $image.RotateFlip([System.Drawing.RotateFlipType]$flip) }
        }
        $scale = [Math]::Min(1.0, $MaxEdge / [Math]::Max($image.Width, $image.Height))
        $width = [int][Math]::Round($image.Width * $scale)
        $height = [int][Math]::Round($image.Height * $scale)
        $bitmap = New-Object System.Drawing.Bitmap $width, $height
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.Clear([System.Drawing.Color]::Black)
                $graphics.DrawImage($image, 0, 0, $width, $height)
            } finally {
                $graphics.Dispose()
            }
            $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
            $params = New-Object System.Drawing.Imaging.EncoderParameters 1
            $params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), ([long]$JpegQuality)
            $bitmap.Save($Target, $codec, $params)
        } finally {
            $bitmap.Dispose()
        }
    } finally {
        $image.Dispose()
    }
}

# ---------------------------------------------------------------- settings

New-Item -ItemType Directory -Force $CacheDir | Out-Null
$config = Get-KitConfig
if ($Folder) { $config.photoFolder = (Resolve-Path $Folder).Path }
if (-not $config.photoFolder) { $config.photoFolder = Join-Path ([Environment]::GetFolderPath('MyPictures')) 'CleanHome' }
if ($Ip) {
    if ($Ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$' -or -not ($Ip -as [System.Net.IPAddress])) { throw "Not an IPv4 address: $Ip" }
    $config.ip = $Ip
}
Save-KitConfig $config
if (-not $config.ip) { throw 'No projector address known. Pass -Ip <address> (find it with .\find-projector.ps1).' }

if (-not (Test-Path $config.photoFolder)) {
    New-Item -ItemType Directory -Force $config.photoFolder | Out-Null
    Write-Host "Photo folder created: $($config.photoFolder) - put photos into it and run this script again."
    return
}

$script:Adb = Find-Adb

# ---------------------------------------------------------------- convert

$root = (Resolve-Path $config.photoFolder).Path
$all = @(Get-ChildItem $root -Recurse -File)
$photos = @($all | Where-Object { $_.Extension -match '^\.(jpe?g|png|bmp|tiff?)$' })
$unsupported = @($all | Where-Object { $_.Extension -match '^\.(heic|heif|webp|avif|raw|cr2|nef|arw)$' })
if ($unsupported.Count -gt 0) {
    Write-Warning "$($unsupported.Count) file(s) skipped (format not supported, please export as JPEG): $(($unsupported | Select-Object -First 5 -ExpandProperty Name) -join ', ')"
}

$wanted = @{}
$converted = 0
$failed = 0
foreach ($photo in $photos) {
    $name = Get-StableName $photo $root
    $target = Join-Path $CacheDir $name
    if (-not (Test-Path $target)) {
        try {
            Convert-Photo $photo.FullName $target
            $converted++
        } catch {
            Write-Warning "Could not convert $($photo.Name): $($_.Exception.Message)"
            $failed++
            continue
        }
    }
    $wanted[$name] = $true
}
Get-ChildItem $CacheDir -File | Where-Object { -not $wanted.ContainsKey($_.Name) } | Remove-Item -Force

# ---------------------------------------------------------------- mirror to the device

$script:Serial = "$($config.ip):5555"
Invoke-Adb -Arguments @('connect', $script:Serial) -AllowFailure | Out-Null
$state = ((Invoke-Adb -Arguments @('-s', $script:Serial, 'get-state') -AllowFailure) -join ' ').Trim()
if ($state -ne 'device') { throw "Projector at $($script:Serial) is not reachable ($state). Is it on, and is network debugging enabled?" }

Invoke-Shell "mkdir -p $RemoteDir" | Out-Null
$remote = @(Invoke-Shell "ls -1 $RemoteDir" -AllowFailure | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[0-9a-f]{16}\.jpg$' })
$pushed = 0
foreach ($name in $wanted.Keys) {
    if ($remote -notcontains $name) {
        Invoke-Adb -Arguments @('-s', $script:Serial, 'push', (Join-Path $CacheDir $name), "$RemoteDir/$name") | Out-Null
        $pushed++
    }
}
$removed = 0
foreach ($name in $remote) {
    if (-not $wanted.ContainsKey($name)) {
        Invoke-Shell "rm -f '$RemoteDir/$name'" | Out-Null
        $removed++
    }
}

Write-Host "Folder: $root"
Write-Host "Photos: $($wanted.Count) | newly converted: $converted | copied: $pushed | removed from device: $removed | failed: $failed"
