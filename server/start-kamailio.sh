#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
chmod +x detect-lan-ip.sh 2>/dev/null || true
IP="$(./detect-lan-ip.sh || true)"
if [[ -z "${IP}" ]]; then
  echo "没有探测到校园网 IPv4。请连校园网后再跑，或手工把 IP 写进 kamailio/public-ip.defs。" >&2
  exit 1
fi
printf '%s\n' "#!substdef \"!PUBLIC_IP!${IP}!g\"" > kamailio/public-ip.defs
printf 'PUBLIC_IP=%s\n' "$IP" > .env
echo "探测到校园网 IP（Kamailio advertise / 客户端应填的 SIP 服务器）：${IP}"
echo "会议室 RTP 用同一地址，UDP 21000-21099。换 Wi-Fi 后重新跑本脚本。不要把这个地址提交进 git。"
docker compose --profile mcu up -d --force-recreate freeswitch
GW="$(docker inspect kamailio --format '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}' | tr -d '\r')"
if [[ -z "${GW}" ]]; then
  echo "没有读到 Docker 网关。会议室媒体无法回到本机。" >&2
  exit 1
fi
printf '%s\n' "#!substdef \"!PUBLIC_IP!${IP}!g\"" "#!substdef \"!GATEWAY_IP!${GW}!g\"" > kamailio/public-ip.defs
echo "Docker 网关（仅本机进房间时，FreeSWITCH 把媒体送到这里）：${GW}"
docker compose restart kamailio
docker compose --profile mcu ps
