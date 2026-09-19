#!/usr/bin/env bash
# 与 client CampusLan 同一套规则：优先 10.0.0.0/8，跳过回环/链路本地/Docker/VM/VMware 154+204。
set -euo pipefail

skip_iface() {
  local n
  n="$(echo "$1" | tr '[:upper:]' '[:lower:]')"
  case "$n" in
    lo|lo0|docker*|vmnet*|vbox*|veth*|br-*|bridge*|utun*|awdl*|llw*|vethernet*|tun*|tap*) return 0 ;;
  esac
  return 1
}

skip_ip() {
  case "$1" in
    127.*|169.254.*|192.168.154.*|192.168.204.*) return 0 ;;
  esac
  return 1
}

best10=""
best_rfc=""

add_ip() {
  local ip="$1"
  skip_ip "$ip" && return 0
  case "$ip" in
    10.*)
      if [[ -z "$best10" ]]; then best10="$ip"; fi
      ;;
    172.1[6-9].*|172.2[0-9].*|172.3[0-1].*|192.168.*)
      if [[ -z "$best_rfc" ]]; then best_rfc="$ip"; fi
      ;;
  esac
}

if command -v ifconfig >/dev/null 2>&1; then
  current=""
  while IFS= read -r line; do
    if [[ "$line" =~ ^([a-zA-Z0-9_.-]+): ]]; then
      current="${BASH_REMATCH[1]}"
    fi
    if [[ -n "$current" ]] && skip_iface "$current"; then
      continue
    fi
    if [[ "$line" =~ inet[[:space:]]+([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+) ]]; then
      add_ip "${BASH_REMATCH[1]}"
    fi
  done < <(ifconfig)
elif command -v ip >/dev/null 2>&1; then
  while read -r iface ip; do
    skip_iface "$iface" && continue
    add_ip "${ip%%/*}"
  done < <(ip -4 -o addr show | awk '{print $2,$4}')
fi

if [[ -n "$best10" ]]; then
  printf '%s\n' "$best10"
elif [[ -n "$best_rfc" ]]; then
  printf '%s\n' "$best_rfc"
fi
