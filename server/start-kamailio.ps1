param([switch]$NoWatch)
# 与 client CampusLan、server/detect-lan-ip.sh 同一套规则：
# 优先 10.0.0.0/8，跳过回环 / 169.254 / Docker/VM 网卡 / VMware 192.168.154 与 192.168.204。
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

. (Join-Path $PSScriptRoot 'lan-ip.ps1')
$ip = Get-SipLanIp
if (-not $ip) {
    Write-Error "没有探测到校园网 IPv4。请连校园网后再跑。"
    exit 1
}

Set-Content -Path (Join-Path $PSScriptRoot ".env") -Value "PUBLIC_IP=$ip" -Encoding ascii
Write-Host "探测到校园网 IP（Kamailio advertise / 客户端应填的 SIP 服务器）：$ip"
Write-Host "会议室 RTP 媒体端口范围: UDP 21000-21099"

# 1. 确保全量服务与网络预先创建就绪 (避免全新机器 inspect 失败)
docker compose --profile mcu up -d
if ($LASTEXITCODE -ne 0) { throw 'Docker services did not start; network readiness was not updated.' }

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
Write-Host "地址仅供本机部署使用，不要提交进 git。"

# 3. 重启 Kamailio 以加载最新的 public-ip.defs 宏定义
docker compose restart kamailio
if ($LASTEXITCODE -ne 0) { throw 'Kamailio did not restart; network readiness was not updated.' }
docker compose --profile mcu ps
if ($LASTEXITCODE -ne 0) { throw 'Docker service status could not be checked.' }
Set-Content -LiteralPath (Join-Path $PSScriptRoot '.lan-ready-ip') -Value $ip -Encoding ascii
if (-not $NoWatch) {
    $watchLock = $null
    try { $watchLock = [IO.File]::Open((Join-Path $PSScriptRoot '.lan-ip-watch.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch [IO.IOException] { Write-Host 'LAN IP watcher is already running.' }
    if ($watchLock) {
        $watchLock.Dispose()
        $shellExe = (Get-Process -Id $PID).Path
        Start-Process -FilePath $shellExe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+(Join-Path $PSScriptRoot 'watch-lan-ip.ps1')+'"')) `
            -WorkingDirectory $PSScriptRoot -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $PSScriptRoot '.lan-ip-watch.log') `
            -RedirectStandardError (Join-Path $PSScriptRoot '.lan-ip-watch.err.log') | Out-Null
        Write-Host 'LAN IP watcher started. Stable address changes refresh Kamailio and FreeSWITCH automatically.'
    }
}
