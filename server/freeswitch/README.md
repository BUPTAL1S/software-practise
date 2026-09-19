# FreeSWITCH

只开一个 MCU 会议室，不做呼叫中心。

## 会议

- 应用：`mod_conference`
- 房间名与 SIP 用户一致：`room-<id>`
- `max-members=8`，第 9 人拒绝
- 视频布局 3×3；分辨率 640×360 或 720p30
- 两人进房时可用「先直通、再拼画布」，不要额外产品逻辑

## 端口（与 Kamailio 对齐后写死）

- SIP：内部 profile（常见 5080 或 5060，不可与 Kamailio 抢 5060）
- ESL：`8021`，密码告诉后台角色

## 镜像

compose 里是占位镜像，阶段 1 换成组内能拉到、带 `mod_conference` 视频的 tag，并回写 [docs/deps.md](../../docs/deps.md)。
