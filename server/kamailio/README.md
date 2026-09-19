# Kamailio

阶段 1：REGISTER + 一对一音频。域 `lab`，客户端只连本机映射的 **5060 UDP/TCP**。

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

`room-*` 阶段 1 直接回 `480 MCU not ready in phase 1`，不进 FreeSWITCH。  
MCU 以后：`docker compose --profile mcu up -d`。

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

## 端口只通 127.0.0.1、不通局域网 IP

Docker Desktop + WSL2 常见。对端仍然填 **启动脚本打印的局域网 IP**，不要把 Domain 改成 `127.0.0.1`。

管理员 CMD 放行 Public 防火墙 5060（校园网 `BUPT-mobile` 常是 Public）：

```text
netsh advfirewall firewall add rule name="Kamailio SIP 5060 TCP" dir=in action=allow protocol=TCP localport=5060 profile=public
netsh advfirewall firewall add rule name="Kamailio SIP 5060 UDP" dir=in action=allow protocol=UDP localport=5060 profile=public
```

必要时再对脚本打印的地址做 portproxy 到 `127.0.0.1:5060`。
