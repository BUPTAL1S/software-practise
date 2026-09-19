# 与 client CampusLan、server/detect-lan-ip.sh 同一套规则：
# 优先 10.0.0.0/8，跳过回环 / 169.254 / Docker/VM 网卡 / VMware 192.168.154 与 192.168.204。
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Skip-Iface([string]$name) {
    $n = $name.ToLowerInvariant()
    return $n -match 'loopback|docker|vmnet|vbox|vethernet|hyper-v|vmware|virtualbox|bluetooth|isatap|teredo'
}

function Skip-Ip([string]$ip) {
    return $ip.StartsWith("127.") -or $ip.StartsWith("169.254.") -or
        $ip.StartsWith("192.168.154.") -or $ip.StartsWith("192.168.204.")
}

$best10 = $null
$bestRfc = $null
Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -and -not (Skip-Iface $_.InterfaceAlias) -and -not (Skip-Ip $_.IPAddress) } |
    ForEach-Object {
        $ip = $_.IPAddress
        if ($ip.StartsWith("10.") -and -not $best10) { $best10 = $ip }
        elseif (-not $bestRfc -and (
            $ip.StartsWith("192.168.") -or $ip -match '^172\.(1[6-9]|2[0-9]|3[0-1])\.')) {
            $bestRfc = $ip
        }
    }

$ip = $best10
if (-not $ip) { $ip = $bestRfc }
if (-not $ip) {
    Write-Error "没有探测到校园网 IPv4。请连校园网后再跑。"
    exit 1
}

$defs = @"
#!substdef "!PUBLIC_IP!$ip!g"
"@
Set-Content -Path (Join-Path $PSScriptRoot "kamailio\public-ip.defs") -Value $defs -Encoding ascii
Set-Content -Path (Join-Path $PSScriptRoot ".env") -Value "PUBLIC_IP=$ip" -Encoding ascii
Write-Host "探测到校园网 IP（Kamailio advertise / 客户端应填的 SIP 服务器）：$ip"
Write-Host "换 Wi-Fi 后重新跑本脚本。不要把这个地址提交进 git。"
docker compose up -d kamailio
docker compose ps
