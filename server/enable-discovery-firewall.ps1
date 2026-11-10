# Run once in an administrator PowerShell on the service host, if LAN queries are blocked.
$ErrorActionPreference = 'Stop'
$ruleName = 'SIPConnect-LAN-Discovery'
if (-not (Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name $ruleName -DisplayName 'SIP Connect LAN discovery (UDP 39771)' `
        -Direction Inbound -Action Allow -Protocol UDP -LocalPort 39771 -RemoteAddress LocalSubnet -Profile Any | Out-Null
}
Write-Output 'LAN discovery firewall rule is ready. HTTP 8080, SIP and media rules remain independently required.'
