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

Set-Content -Path (Join-Path $PSScriptRoot ".env") -Value "PUBLIC_IP=$ip" -Encoding ascii
Write-Host "探测到校园网 IP（Kamailio advertise / 客户端应填的 SIP 服务器）：$ip"
Write-Host "会议室 RTP 媒体端口范围: UDP 21000-21099"

# 1. 确保全量服务与网络预先创建就绪 (避免全新机器 inspect 失败)
docker compose --profile mcu up -d

# 2. 稳健获取 Docker 网络网关 IP (用于宿主机与容器间回环媒体穿透)
$gw = (docker inspect kamailio --format '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}' 2>$null)
if (-not $gw) {
    $gw = (docker inspect freeswitch --format '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}' 2>$null)
}
if (-not $gw) {
    $gw = (docker exec kamailio ip route show default 2>$null | Select-String -Pattern 'default\s+via\s+([0-9.]+)' | ForEach-Object { $_.Matches.Groups[1].Value })
}
if (-not $gw) {
    $gw = (docker network inspect server_default --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}' 2>$null)
}
if (-not $gw) {
    $cip = (docker inspect kamailio --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' 2>$null)
    if ($cip -and $cip -match '^(\d+\.\d+\.\d+)\.\d+$') {
        $gw = "$($matches[1]).1"
    }
}
if (-not $gw) {
    $gw = "172.22.0.1"
}
$gw = $gw.Trim()

$defs = @"
#!substdef "!PUBLIC_IP!$ip!g"
#!substdef "!GATEWAY_IP!$gw!g"
"@
Set-Content -Path (Join-Path $PSScriptRoot "kamailio\public-ip.defs") -Value $defs -Encoding ascii
Write-Host "Docker 网关（宿主机客户端进房时，FreeSWITCH 将 RTP 送至此地址）：$gw"
Write-Host "换 Wi-Fi 后重新跑本脚本。不要把这个地址提交进 git。"

# 3. 重启 Kamailio 以加载最新的 public-ip.defs 宏定义
docker compose restart kamailio
docker compose --profile mcu ps
