# Kamailio

阶段 1：REGISTER + 一对一音频。域 `lab`，客户端只连本机映射的 **5060 UDP/TCP**。

## 测试账号（静态，无鉴权）

密码任意非空即可。后台用户表不同步。

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

同机双开先用 1001 / 1002。双机验收各用一个号。

## 两个 IP（必须实测，禁止抄示例）

| 用途 | 写在哪里 | 怎么取 |
| --- | --- | --- |
| Docker 内部 IP | 仅用于核对；`listen` 绑 `0.0.0.0` | `docker inspect kamailio --format "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}"`（compose 桥接常见 `172.20.0.2`，会变） |
| Windows 局域网 IP | `kamailio.cfg` 顶部 `PUBLIC_IP`；Linphone 代理也填这个 | `ipconfig` 里实际上网网卡 |

不要用 VMware 的 `192.168.154.1` / `192.168.204.1`。  
不要把老师讲义里的 `10.129.52.95` 写进配置。  
换 Wi-Fi 后重查 `ipconfig`，改 `PUBLIC_IP`，然后 `docker compose restart kamailio`。

当前本机写入值（2026-09-19）：

- `listen=0.0.0.0:5060`（容器内）
- `PUBLIC_IP=10.21.219.104`（WLAN advertise）
- 本次容器地址：`172.20.0.2`（仅记录，不要写进 listen）

## 启动

```bash
cd server
docker compose up -d kamailio
```

验收容器：

```bash
docker compose ps
docker exec kamailio grep -n "^listen=\|^#!substdef" /etc/kamailio/kamailio.cfg
```

`room-*` 阶段 1 直接回 `480 MCU not ready in phase 1`，不进 FreeSWITCH。  
MCU 以后：`docker compose --profile mcu up -d`。

## Linphone 怎么填

Domain 必须是 `lab`，不要只填 IP（否则身份会变成 `sip:1001@10.x`）。

| 字段 | 值 |
| --- | --- |
| Username | `1001` 或 `1002` |
| Domain | `lab` |
| Registrar / Outbound proxy | 双机：`<WINDOWS_IP>:5060`（当前 `10.21.219.104:5060`）。同机自测：`127.0.0.1:5060` |
| Transport | 先 TCP（本机已测通），再 UDP；不要 TLS |
| Password | 任意非空 |

**Domain 必须是 `lab`。** 同机自测可以把代理写成 `127.0.0.1`，不要把 Domain 改成 `127.0.0.1`。

2026-09-19 本机信令自测（TCP `127.0.0.1:5060`）：

- `REGISTER sip:1001@lab` / `1002@lab` → `200 OK`
- `INVITE sip:room-101@lab` → `480 MCU not ready in phase 1`
- `INVITE sip:1002@lab`（两端已注册）→ `100 Trying`（lookup + relay）

`10.21.219.104:5060` 仍需管理员放行防火墙 / portproxy 后才能给第二台电脑用。

呼叫对方：`1002@lab` 或 `1001@lab`。

## 阶段 1 验收

1. 两个账号显示已注册。
2. `1001` 呼叫 `1002@lab`，能振铃、接通、有声音。
3. 反向呼叫同样通。

Linphone 同机双开：两个账号都填 Domain=`lab`，代理=`127.0.0.1:5060`，Transport=TCP。双向音频需在本机点通一次（脚本只能验信令，验不了 RTP）。

## 端口只通 127.0.0.1、不通局域网 IP

Docker Desktop + WSL2 常见情况。Linphone **仍然用 Windows 局域网 IP**，不要改成 `127.0.0.1` 当 SIP 域。

```text
docker compose ps
powershell -Command "Test-NetConnection -ComputerName 10.21.219.104 -Port 5060"
```

若 `TcpTestSucceeded` 为 False：管理员 CMD 放行 Public 防火墙 5060，必要时：

```text
netsh advfirewall firewall add rule name="Kamailio SIP 5060 TCP" dir=in action=allow protocol=TCP localport=5060 profile=public
netsh advfirewall firewall add rule name="Kamailio SIP 5060 UDP" dir=in action=allow protocol=UDP localport=5060 profile=public
netsh interface portproxy add v4tov4 listenaddress=10.21.219.104 listenport=5060 connectaddress=127.0.0.1 connectport=5060
```

校园网 `BUPT-mobile` 是 Public。双机验收时对端也要能访问这台机器的 5060。
