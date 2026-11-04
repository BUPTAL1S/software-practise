# FreeSWITCH MCU 与 ESL 配置

负责阶段 5 视频会议 MCU 与 ESL 事件通知。

## 架构与约定

1. **会议应用**：FreeSWITCH `mod_conference`，Profile 名称 `video-mcu-3x3`。
2. **房间命名**：与 SIP 用户保持严格一致：`room-<id>`（如 `room-101`，由后台创建生成）。
3. **容量限制**：`max-members=8`，第 9 人进入拒绝（回送忙音/拒绝）。
4. **视频画布**：视频模式 `video-mode=mux`，布局 `video-layout-name=group:sip-adaptive`，按视频人数选择 1/2/4/9 格；画布维持 960×540、15fps、1mb，支持 H.264 基线协商。保留历史 profile 名 `video-mcu-3x3`，不改变拨号路由。
5. **容器镜像与网络安全端口**：
   - 镜像版本：`safarov/freeswitch:1.11.3`（阶段 5 锁定版本，与 `docs/deps.md` 一致）。
   - SIP 端口：`5080 UDP/TCP`（与 Kamailio 的 5060 明确隔离，Kamailio 将 `room-*` 的 INVITE 路由至 `freeswitch:5080`）。
   - ESL 端口：`127.0.0.1:8021 TCP`，宿主机端口映射仅绑定回环地址 `127.0.0.1`，配合 `acl.conf.xml` 放行私网（`10.0.0.0/8`、`127.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`），供 Docker 网桥访问。宿主机端口只绑在 `127.0.0.1`，校园网其他机器连不进 8021。
   - 声音目录增量合成：主机自定义 `sounds/` 提示音由 entrypoint 增量注入 `/usr/share/freeswitch/sounds/`，不破坏容器内置音效。

## 配置文件结构

- `freeswitch.xml`：根配置文件（包含 vars、autoload_configs、dialplan、chatplan、directory、languages）
- `vars.xml`：全局变量定义（域 `lab`，端口 `5080`，密码 `ClueCon`）
- `autoload_configs/conference.conf.xml`：自适应布局组、8 人上限与满员拒绝提示
- `autoload_configs/conference_layouts.conf.xml`：独立定义单人、双人、四格和九格；无需依赖镜像内置布局名称
- `autoload_configs/modules.conf.xml`：载入核心模块、`mod_conference`、`mod_av`（H.264 视频编码与混流合成）、`mod_png`、`mod_event_socket`、`mod_opus` 等
- `autoload_configs/event_socket.conf.xml`：ESL 服务配置（8021 端口与鉴权密码）
- `autoload_configs/sofia.conf.xml`：Sofia SIP 模块载入
- `sip_profiles/internal.xml`：SIP 内部 Profile（端口 5080，支持 PCMU/PCMA/H264）
- `dialplan/default.xml`：拨号方案，正则匹配 `^(room-.*)$` 自动转入 `conference`

## 启动方式

在 `server/` 目录下执行：

```bash
docker compose --profile mcu up -d
```

验证容器状态：
```bash
docker compose ps
# 检查 ESL 端口连通性
nc -zv 127.0.0.1 8021
```

## 布局变更验收

布局坐标与分组格式依据 [FreeSWITCH 官方配置](https://github.com/signalwire/freeswitch/blob/v1.10.12/conf/vanilla/autoload_configs/conference_layouts.conf.xml)。1 人占满画布，2 人并排居中，3–4 人四格，5–8 人九格。画布、帧率、码率与容量上限保持现值，避免未经测量增加 MCU 编码负担。

本次仅在本地校验 XML、组引用、格子范围/不重叠和拨号 profile；没有部署同学服务器。部署负责人应在维护窗口更新两个 XML，再按现有 entrypoint 重新载入容器配置，验证人数增减、纯语音成员、摄像头开关和第 9 人拒绝。`reloadxml` 不保证已经建立的会议重新读取 profile，应使用新会议验收。

## ESL 事件交互说明

后台 Spring Boot 连接 `8021` 端口，完成文本协议交互：
1. 建立 TCP 连接，收到 `Content-Type: auth/request`；
2. 发送 `auth ClueCon\n\n`，收到 `+OK accepted`；
3. 发送 `event plain CUSTOM conference::maintenance\n\n`，收到 `+OK event listener enabled plain`；
4. 实时接收 `Action: add-member` 与 `Action: del-member`，自动更新 SQLite `rooms` 表中的 `member_count` 与峰值人数。
