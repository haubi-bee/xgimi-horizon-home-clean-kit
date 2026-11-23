<#
.SYNOPSIS
    Finds Google Cast devices (Android TV projectors included) in the local network.

.DESCRIPTION
    Scans the /24 network(s) of this PC's active IPv4 interfaces for devices answering
    http://<ip>:8008/setup/eureka_info and prints their names. It also checks whether port 5555
    (network debugging) is open, which setup.ps1 needs.

.PARAMETER Subnet
    Scan this /24 network instead, given as its first three octets, e.g. 192.168.1

.PARAMETER TimeoutMs
    Per-device timeout in milliseconds (default 1500).

.EXAMPLE
    .\find-projector.ps1

.EXAMPLE
    .\find-projector.ps1 -Subnet 10.0.0
#>
[CmdletBinding()]
param(
    [string]$Subnet,
    [ValidateRange(200, 10000)][int]$TimeoutMs = 1500
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

function Get-LocalSubnets {
    $subnets = @()
    foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
        if ($nic.OperationalStatus -ne 'Up') { continue }
        if ($nic.NetworkInterfaceType -eq 'Loopback' -or $nic.NetworkInterfaceType -eq 'Tunnel') { continue }
        foreach ($address in $nic.GetIPProperties().UnicastAddresses) {
            if ($address.Address.AddressFamily -ne 'InterNetwork') { continue }
            $octets = $address.Address.ToString().Split('.')
            if ($octets[0] -eq '169' -and $octets[1] -eq '254') { continue } # link-local, no DHCP
            $subnets += ($octets[0..2] -join '.')
        }
    }
    return @($subnets | Select-Object -Unique)
}

function Test-TcpPort([string]$Address, [int]$Port, [int]$Timeout) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($Address, $Port)
        return ($task.Wait($Timeout) -and $client.Connected)
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

if ($Subnet) {
    if ($Subnet -notmatch '^\d{1,3}\.\d{1,3}\.\d{1,3}$') { throw "Use the first three octets, e.g. 192.168.1 (got '$Subnet')." }
    $subnets = @($Subnet)
} else {
    $subnets = Get-LocalSubnets
}
if ($subnets.Count -eq 0) { throw 'No active IPv4 network found on this PC.' }

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromMilliseconds($TimeoutMs)
$found = @()
try {
    foreach ($net in $subnets) {
        Write-Host "Scanning $net.1-254 ..."
        $requests = @{}
        foreach ($host4 in 1..254) {
            $address = "$net.$host4"
            $requests[$address] = $client.GetStringAsync("http://${address}:8008/setup/eureka_info")
        }
        try {
            [void][System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]@($requests.Values), $TimeoutMs + 2000)
        } catch {
            # Unreachable addresses fail their task; only the successful ones matter.
        }
        foreach ($address in $requests.Keys) {
            $task = $requests[$address]
            if ($task.Status -ne 'RanToCompletion') { continue }
            try {
                $info = $task.Result | ConvertFrom-Json
            } catch {
                continue
            }
            $name = if ($info.PSObject.Properties['name'] -and $info.name) { [string]$info.name } else { '(no name)' }
            $found += [pscustomobject]@{
                Ip               = $address
                Name             = $name
                NetworkDebugging = if (Test-TcpPort $address 5555 $TimeoutMs) { 'on' } else { 'off' }
            }
        }
    }
} finally {
    $client.Dispose()
}

if ($found.Count -eq 0) {
    Write-Host 'No Cast devices found. Is the projector on and in the same network as this PC?'
    return
}
$found | Sort-Object { [version]$_.Ip } | Format-Table -AutoSize
if ($found.Count -eq 1) {
    $only = $found[0]
    if ($only.NetworkDebugging -eq 'off') {
        Write-Host 'Turn on network debugging on the projector (Developer options), then run:'
    } else {
        Write-Host 'Next step:'
    }
    Write-Host "  .\setup.ps1 -Ip $($only.Ip)"
}
