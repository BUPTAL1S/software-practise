# FreeSWITCH

阶段 4 只开 ESL，给后台订房间事件。不做呼叫中心，一对一不进这里。`sip:room-*@lab` 仍由 Kamailio 回 `480`，阶段 5 再把房间 INVITE 转进会议室。

## ESL（后台用这个）

| 项 | 值 |
| --- | --- |
| 地址 | `127.0.0.1:8021` |
| 密码 | `ClueCon` |
| 订阅 | `CHANNEL_CREATE`、`CHANNEL_DESTROY`、`conference::maintenance` |

`conference::maintenance` 里看入会 / 离会（`add-member` / `del-member`）。后台只收事件，不要发 `originate` 之类的呼叫命令。

镜像是 `safarov/freeswitch:1.10.12`。容器第一次启动会把自带 vanilla 配置拷到容器内的 `/etc/freeswitch`，密码就是上面的 `ClueCon`。镜像默认把 ESL 绑在 `::`，这台 Docker 没有 IPv6，所以 `lab-entrypoint.sh` 改成 `0.0.0.0:8021`。从宿主机连进来时，容器看到的是 Docker 网桥地址，默认 ACL 会拒绝，因此同时改成允许 `rfc1918.auto`。不要把空的 `server/freeswitch/` 整目录挂到 `/etc/freeswitch`，否则会盖掉这份配置。

## 启动

在 `server/`：

```bash
docker compose up -d freeswitch
```

验收：连上 `127.0.0.1:8021`，发送 `auth ClueCon`，应看到 `+OK accepted`。

SIP 对外只映射 `5080`，不占用 Kamailio 的 `5060`。

## 阶段 5 再做的会议

- 应用：`mod_conference`（镜像里已加载）
- 房间名与 SIP 用户一致：`room-<id>`
- `max-members=8`，第 9 人拒绝
- 视频布局 3×3；分辨率 640×360 或 720p30
- 两人进房时可用「先直通、再拼画布」，不要额外产品逻辑
