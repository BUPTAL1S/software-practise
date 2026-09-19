# Kamailio

完整 `kamailio.cfg` 阶段 1 由信令角色补齐。这里只定行为，避免抄一份用不上的全量配置。

## 必须有的三条

1. `REGISTER` 进 usrloc（内存即可，阶段 1 不必上 DB）
2. `INVITE` 用户 `sip:<user>@lab` → lookup 后 `relay` 到联系地址
3. 若 R-URI 用户部分匹配 `room-` 前缀 → `relay` 到本机 FreeSWITCH SIP（默认 `5080` 或 FS 内部 profile 端口，compose 起来后写死一处）

一对一不要 `t_relay` 到 FreeSWITCH。

## 域与端口

- 域：`lab`
- 对客户端：`5060` UDP（需要的话再开 TCP）
- 镜像：见上级 `docker-compose.yml`（当前占位 `5.8.3-alpine`）

`network_mode: host` 是为了 SIP/RTP 少一层 NAT。若 Windows Docker 不能 host 网络，改桥接并在文档里写对外 IP。
