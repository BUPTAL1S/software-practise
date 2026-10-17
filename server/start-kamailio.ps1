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
Write-Host "会议室 RTP 用同一地址，UDP 21000-21099。换 Wi-Fi 后重新跑本脚本。不要把这个地址提交进 git。"
$dockerBin = Join-Path $env:ProgramFiles "Docker\Docker\resources\bin"
if (Test-Path (Join-Path $dockerBin "docker.exe")) {
    $env:PATH = "$dockerBin;" + $env:PATH
} elseif (-not (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
    Write-Error "找不到 docker.exe。请先安装并启动 Docker Desktop。"
    exit 1
}
$rtpRule = "SIPIM-MCU-RTP-21000-21099"
netsh advfirewall firewall show rule name="$rtpRule" | Out-Null
if ($LASTEXITCODE -ne 0) {
    netsh advfirewall firewall add rule name="$rtpRule" dir=in action=allow protocol=UDP localport=21000-21099 profile=any | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "没能写防火墙规则（需要管理员）。组员进房间前，用管理员 CMD 放行 UDP 21000-21099。"
    }
}
docker compose --profile mcu up -d --force-recreate freeswitch
$gw = (docker inspect kamailio --format '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}').Trim()
if (-not $gw) {
    Write-Error "没有读到 Docker 网关。会议室媒体无法回到本机。"
    exit 1
}
$defs = @"
#!substdef "!PUBLIC_IP!$ip!g"
#!substdef "!GATEWAY_IP!$gw!g"
"@
Set-Content -Path (Join-Path $PSScriptRoot "kamailio\public-ip.defs") -Value $defs -Encoding ascii
Write-Host "Docker 网关（仅本机进房间时，FreeSWITCH 把媒体送到这里）：$gw"
docker compose restart kamailio
docker compose --profile mcu ps
