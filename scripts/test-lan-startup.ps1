# Offline checks: copy scripts to a fixture and replace Docker/NIC commands, never touch live services.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path $repo ('client\target\lan-startup-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $fixture 'kamailio') -Force | Out-Null
foreach ($name in @('start-kamailio.ps1','lan-ip.ps1')) { Copy-Item -LiteralPath (Join-Path $repo ('server\'+$name)) -Destination $fixture }
$global:discoveryTestIp = '10.20.30.40'
$global:failDiscoveryDocker = $false
function global:Get-NetIPAddress { @(
    [pscustomobject]@{IPAddress='198.18.0.1';InterfaceAlias='TUN';AddressState='Preferred'},
    [pscustomobject]@{IPAddress=$global:discoveryTestIp;InterfaceAlias='WLAN';AddressState='Preferred'}
) }
function global:docker {
    if ($global:failDiscoveryDocker) { $global:LASTEXITCODE=1; return }
    if ($args[0] -eq 'inspect') { Write-Output '172.22.0.1' }
    $global:LASTEXITCODE=0
}
$initial = Get-Location
try {
    & (Join-Path $fixture 'start-kamailio.ps1') -NoWatch
    if ((Get-Content -LiteralPath (Join-Path $fixture '.lan-ready-ip') -Raw).Trim() -ne '10.20.30.40') { throw 'Initial address not marked ready' }
    $global:discoveryTestIp='10.20.30.41'
    & (Join-Path $fixture 'start-kamailio.ps1') -NoWatch
    if ((Get-Content -LiteralPath (Join-Path $fixture '.env') -Raw).Trim() -ne 'PUBLIC_IP=10.20.30.41') { throw 'MCU environment not updated' }
    if ((Get-Content -LiteralPath (Join-Path $fixture 'kamailio\public-ip.defs') -Raw) -notmatch '10\.20\.30\.41') { throw 'Kamailio address not updated' }
    $global:failDiscoveryDocker=$true; $global:discoveryTestIp='10.20.30.42'; $failed=$false
    try { & (Join-Path $fixture 'start-kamailio.ps1') -NoWatch } catch { $failed=$true }
    if (-not $failed -or (Get-Content -LiteralPath (Join-Path $fixture '.lan-ready-ip') -Raw).Trim() -ne '10.20.30.41') { throw 'Failure incorrectly marked the new address ready' }
    Write-Output 'LAN_STARTUP_PASS initial/change/failure-retry markers; no live Docker or NIC changes'
} finally {
    Set-Location $initial
    Remove-Item Function:\Get-NetIPAddress,Function:\docker -ErrorAction SilentlyContinue
    Remove-Variable discoveryTestIp,failDiscoveryDocker -Scope Global -ErrorAction SilentlyContinue
}
