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
echo "换 Wi-Fi 后重新跑本脚本。不要把这个地址提交进 git。"
docker compose --profile mcu up -d
docker compose ps
