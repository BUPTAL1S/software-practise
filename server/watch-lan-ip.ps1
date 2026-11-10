[CmdletBinding()]
param([int]$IntervalSeconds = 5)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lan-ip.ps1')
$lock = $null
try {
    # Only one watcher owns this checkout. The handle releases automatically after a crash.
    try { $lock = [IO.File]::Open((Join-Path $PSScriptRoot '.lan-ip-watch.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch [IO.IOException] { exit 0 }
    Set-Content -LiteralPath (Join-Path $PSScriptRoot '.lan-ip-watch.pid') -Value $PID -Encoding ascii
    $marker = Join-Path $PSScriptRoot '.lan-ready-ip'
    $candidate = ''
    while ($true) {
        try {
            $current = Get-SipLanIp
            $ready = if (Test-Path -LiteralPath $marker) { (Get-Content -LiteralPath $marker -Raw).Trim() } else { '' }
            if ($current -and $current -ne $ready -and $current -eq $candidate) {
                Write-Output ('[{0:o}] LAN address changed to {1}; refreshing signaling/MCU' -f [DateTime]::Now,$current)
                & (Join-Path $PSScriptRoot 'start-kamailio.ps1') -NoWatch
            }
            $candidate = $current
        } catch { Write-Warning ('LAN refresh failed; next poll will retry: ' + $_.Exception.Message) }
        Start-Sleep -Seconds ([Math]::Max(1, $IntervalSeconds))
    }
} finally { if ($lock) { $lock.Dispose() } }
