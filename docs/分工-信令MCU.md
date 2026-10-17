# 分工：信令与 MCU

负责 Kamailio 与 FreeSWITCH。全组共用一台 Docker 服务。

## 做

- `server/docker-compose.yml` 能拉起 Kamailio + FreeSWITCH（不要数据库容器）
- Kamailio：REGISTER / 查找 / 一对一转发
- Kamailio：用户部分 `room-*` 的 INVITE 转到 FreeSWITCH，其它 INVITE 不进 MCU
- FreeSWITCH：一个会议室，`max-members=8`，视频 3×3，640×360 或 720p30（不要 1080p）
- 给后台开 ESL（默认 8021），只用于房间事件
- 维护测试账号（至少 8 个），写在本目录 README，方便双机多开

## 不做

- 不写 JavaFX、不写管理端业务
- 不做技能组、队列、IVR、呼叫中心
- 不把一对一桥进 conference
- 不引入 Jitsi / RTPEngine 集群
- 不把 Kamailio / FreeSWITCH 源码放进本仓库

## 接口

- 域：`lab`
- 房间 URI：`sip:room-<id>@lab`（`<id>` 与后台房间主键或短码一致，开工会定时一下）
- 客户端只连 Kamailio SIP 端口（默认 5060 UDP/TCP）
- ESL：后台只订 `CHANNEL_CREATE` / `CHANNEL_DESTROY` / conference member 增减

配置意图见 [kamailio/README.md](../server/kamailio/README.md) 与 [freeswitch/README.md](../server/freeswitch/README.md)。

## 按阶段交付

| 阶段 | 你交出什么 |
|---|---|
| 1 通话底座 | compose 起来；两个 UA 能经 Kamailio 音频通话 |
| 2 视频界面 | 视频 SDP 能过。约定 PJSIP 2.17 OpenH264：`97 H264/90000`，`profile-level-id=42e01e`，`packetization-mode=1`。一对一不改 SDP、不进 MCU |
| 3 消息文件 | 保持稳定；MESSAGE 由 Kamailio 转发即可 |
| 4 后台统计 | ESL 端口通，供后台订房间事件 |
| 5 房间 MCU | 6–8 路进同一会议室，第 9 人拒绝 |
| 6 交付 | 演示用一键 `docker compose up` |

和客户端对齐端口与账号。和后台对齐房间 id 规则、ESL 密码。
