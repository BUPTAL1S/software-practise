# Kamailio

阶段 5：REGISTER + 一对一音视频直连 + 一对一 MESSAGE + FreeSWITCH 房间 MCU (room-*) 信令路由。域 `lab`，客户端只连本机映射的 **5060 UDP/TCP**。

## 测试账号（静态，无鉴权）

密码任意非空即可。后台用户表阶段 1 种子是 `1001`–`1008`，与这里对齐，但仍不校验 SIP 密码。

| 账号 | SIP URI |
| --- | --- |
| 1001 | `sip:1001@lab` |
| 1002 | `sip:1002@lab` |
| 1003 | `sip:1003@lab` |
| 1004 | `sip:1004@lab` |
| 1005 | `sip:1005@lab` |
| 1006 | `sip:1006@lab` |
| 1007 | `sip:1007@lab` |
| 1008 | `sip:1008@lab` |

同机双开先用 1001 / 1002。双机验收各用一个号。不要两个 UA 抢同一个账号。

## 启动（自动探测校园网 IP）

不要手填 `PUBLIC_IP`，也不要抄别人机器上的地址。换 Wi-Fi 后重新跑脚本。

```bash
cd server
./start-kamailio.sh
```

Windows（PowerShell）：

```powershell
cd server
.\start-kamailio.ps1
```

脚本会：

1. 探测本机校园网 IPv4（优先 `10.0.0.0/8`，跳过 Docker/VMware/回环）
2. 写入 gitignore 的 `kamailio/public-ip.defs` 和 `server/.env`
3. **打印这个 IP**——告诉客户端填到「SIP 服务器」
4. `docker compose up -d kamailio`

`listen` 仍绑 `0.0.0.0:5060`；advertise / alias 用探测到的地址。

不要用 VMware 的 `192.168.154.x` / `192.168.204.x`。不要把讲义里的示例 IP 写进配置。

验收容器：

```bash
docker compose ps
docker exec kamailio grep -n "PUBLIC_IP" /etc/kamailio/public-ip.defs
```

`room-*` 呼叫由 Kamailio 自动路由转发至 FreeSWITCH MCU (`freeswitch:5080`)，进入 3×3 九宫格混流会议室；一对一呼叫走 `lookup("location")` 直连，绝不进入 MCU。  
启动命令：`docker compose --profile mcu up -d`（启动脚本 `./start-kamailio.sh` 会自动带上）。

## 客户端 / Linphone 怎么填

Domain 必须是 `lab`，不要只填 IP（否则身份会变成 `sip:1001@10.x`）。

| 字段 | 值 |
| --- | --- |
| Username | `1001` 或 `1002` |
| Domain | `lab` |
| Registrar / Outbound proxy | 双机：启动脚本打印的校园网 IP，端口 5060。同机自测：`127.0.0.1:5060` |
| Transport | 先 TCP，再 UDP；不要 TLS |
| Password | 任意非空 |

JavaFX 客户端默认 TCP，会记住上次代理。本机 IP 只用于 Contact，不要和「SIP 服务器」搞混。

## 阶段 1 验收

1. 两个账号显示已注册（200）。
2. `1001` 呼叫 `1002`，能振铃、接通、有声音。任一方挂断，对端应变 DISCONNECTED。
3. 反向呼叫同样通。改 cfg 后必须 `docker compose restart kamailio`（或重跑 `start-kamailio`）。

## 阶段 2：视频 SDP

一对一仍是 `INVITE sip:<用户>@lab`，经 `lookup` 后原样 `t_relay`。不进 MCU，不加载 rtpengine / sdpops，不改 SDP。

客户端用 PJSIP **2.17 OpenH264**，Windows 与 macOS 必须是同一套，不要同时再放 ffmpeg / VideoToolbox 的另一套 H.264：

| 项 | 值 |
| --- | --- |
| rtpmap | `97 H264/90000` |
| profile-level-id | `42e01e`（Constrained Baseline，Level 3.0） |
| packetization-mode | `1` |
| fmtp | `profile-level-id=42e01e; packetization-mode=1` |

这是 OpenH264 在 2.17 里的默认值（动态载荷 `PJMEDIA_RTP_PT_H264` = 97）。分号后的空格可有可无，参数值不能换。200 OK 里视频端口变成 0，是对端没编上这套，不是 Kamailio 删的。

媒体仍是两端 RTP 直连，不经过本代理。Contact 继续通告校园网 IP。Windows 防火墙若只放行 5060，信令能通、画面会黑；视频 UDP 打在 Contact 里的媒体端口上。

本机客户端重编原生库、打开 OpenH264 是客户端的事。信令侧只保证上面的视频行能穿过，并且对话内 re-INVITE / ACK / BYE 仍走现有双 Record-Route。

## 阶段 3：一对一 MESSAGE

`MESSAGE sip:<用户>@lab` 发给已注册用户时原样转发，`text/plain` 正文不改，对端的 200 回到主叫。没注册回 404，Kamailio 不存离线消息。文件、图片、语音条走后台 HTTP，不走 SIP，也不做 MSRP。

Contact 里如果写了 `methods` 但没有 `MESSAGE`，REGISTER 时会补上，否则 `method_filtering` 对这条消息回 405。没写 `methods` 的客户端不用改。

## 阶段 5：房间 MCU 会议室路由 (room-*)

1. **信令路由隔离与分流**：
   - 收到 `INVITE sip:room-<id>@lab`（用户部分正则匹配 `^room-`）时，Kamailio 将目标 URI 改写为 `$du = "sip:freeswitch:5080;transport=" + $proto`，执行 `record_route()` 后调用 `relay()` 发送至 FreeSWITCH MCU 容器；
   - 一对一通话（如 `INVITE sip:1002@lab`）严格继续走 `lookup("location")`，由两端 UA 直连，**绝对不进入 FreeSWITCH**；
   - 严格避免架构耦合与串线，保证点对点媒体与 MCU 集中媒体分流清晰。
2. **媒体协商与画面流向**：
   - 一对一通话由客户端之间协商 RTP（音视频直连，不消耗服务器编解码资源）；
   - 多人会议室由 FreeSWITCH MCU 作为 B2BUA 终结媒体，并将最多 8 路参会人画面合成为单路 720p30 H.264 3×3 画面分发给参会端。超额第 9 人进入时直接拒绝。
   - 会议媒体走宿主机 UDP `21000-21099`。FreeSWITCH 在 SDP 里写启动脚本探测到的校园网 IP。本机客户端进房间时有两跳改写：发往 FreeSWITCH 的地址改成 Docker 网关，FreeSWITCH 回答回来的地址改成 `127.0.0.1`。其他电脑保持校园网 IP。一对一仍不改 SDP。

## 端口只通 127.0.0.1、不通局域网 IP

Docker Desktop + WSL2 常见。对端仍然填 **启动脚本打印的局域网 IP**，不要把 Domain 改成 `127.0.0.1`。

管理员 CMD 放行 Public 防火墙 5060（校园网 `BUPT-mobile` 常是 Public）：

```text
netsh advfirewall firewall add rule name="Kamailio SIP 5060 TCP" dir=in action=allow protocol=TCP localport=5060 profile=public
netsh advfirewall firewall add rule name="Kamailio SIP 5060 UDP" dir=in action=allow protocol=UDP localport=5060 profile=public
netsh advfirewall firewall add rule name="SIPIM-MCU-RTP-21000-21099" dir=in action=allow protocol=UDP localport=21000-21099 profile=any
```

必要时再对脚本打印的地址做 portproxy 到 `127.0.0.1:5060`。
