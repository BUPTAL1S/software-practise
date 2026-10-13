#!/bin/sh
set -e
if [ ! -f /etc/freeswitch/freeswitch.xml ]; then
  mkdir -p /etc/freeswitch
  cp -a /usr/share/freeswitch/conf/vanilla/. /etc/freeswitch/
fi
# Docker 端口映射进来的源地址是网桥（172.16/12），不是 127.0.0.1。
# 不改的话默认 ACL loopback.auto 会直接 Access Denied。
sed -i \
  -e 's/name="listen-ip" value="::"/name="listen-ip" value="0.0.0.0"/' \
  -e 's|<!--<param name="apply-inbound-acl" value="loopback.auto"/>-->|<param name="apply-inbound-acl" value="rfc1918.auto"/>|' \
  /etc/freeswitch/autoload_configs/event_socket.conf.xml
exec /docker-entrypoint.sh