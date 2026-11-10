function Get-SipLanIp {
    $addresses = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object {
        $_.IPAddress -and $_.InterfaceAlias -notmatch 'loopback|docker|vmnet|vbox|vethernet|hyper-v|vmware|virtualbox|bluetooth|isatap|teredo|tun|tap' -and
        $_.IPAddress -notmatch '^(127\.|169\.254\.|192\.168\.(154|204)\.|198\.(18|19)\.)' -and
        $_.AddressState -ne 'Tentative' -and $_.AddressState -ne 'Duplicate'
    })
    $preferred = @($addresses | Where-Object {$_.IPAddress.StartsWith('10.')})
    if ($preferred.Count) { return [string]$preferred[0].IPAddress }
    $private = @($addresses | Where-Object {$_.IPAddress -match '^(192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)'})
    if ($private.Count) { return [string]$private[0].IPAddress }
    return ''
}
