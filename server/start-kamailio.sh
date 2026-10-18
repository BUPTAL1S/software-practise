#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
chmod +x detect-lan-ip.sh 2>/dev/null || true
IP="$(./detect-lan-ip.sh || true)"
if [[ -z "${IP}" ]]; then
  echo "没有探测到校园网 IPv4。请连校园网后再跑，或手工把 IP 写进 kamailio/public-ip.defs。" >&2
  exit 1
fi

printf 'PUBLIC_IP=%s\n' "$IP" > .env
echo "探测到校园网 IP（Kamailio advertise / 客户端应填的 SIP 服务器）：${IP}"
echo "会议室 RTP 媒体端口范围: UDP 21000-21099"

# 1. 确保全量服务与网络预先创建就绪 (避免全新机器 inspect 失败)
docker compose --profile mcu up -d

# 2. 稳健获取 Docker 网络网关 IP (用于宿主机与容器间回环媒体穿透)
GW="$(docker inspect kamailio --format '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}' 2>/dev/null | tr -d '\r' || true)"
if [[ -z "${GW}" ]]; then
  GW="$(docker inspect freeswitch --format '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}' 2>/dev/null | tr -d '\r' || true)"
fi
if [[ -z "${GW}" ]]; then
  GW="$(docker exec kamailio ip route show default 2>/dev/null | awk '/default/ {print $3}' | tr -d '\r' || true)"
fi
if [[ -z "${GW}" ]]; then
  GW="$(docker network inspect server_default --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}' 2>/dev/null | tr -d '\r' || true)"
fi
if [[ -z "${GW}" ]]; then
  CIP="$(docker inspect kamailio --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "${CIP}" && "${CIP}" == *.* ]]; then
    GW="${CIP%.*}.1"
  fi
fi
if [[ -z "${GW}" ]]; then
  GW="172.22.0.1"
fi

printf '%s\n' "#!substdef \"!PUBLIC_IP!${IP}!g\"" "#!substdef \"!GATEWAY_IP!${GW}!g\"" > kamailio/public-ip.defs
echo "Docker 网关（宿主机客户端进房时，FreeSWITCH 将 RTP 送至此地址）：${GW}"

# 3. 重启 Kamailio 以加载最新的 public-ip.defs 宏定义
docker compose restart kamailio
docker compose --profile mcu ps
