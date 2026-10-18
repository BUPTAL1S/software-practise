# software-practise

分组课设：Java PC 即时通信（SIP 音视频 + 房间 + 后台）。

- 整体规划与三人分工：[docs/整体规划.md](docs/整体规划.md)
- 开源依赖（钉版本，不拷全仓）：[docs/deps.md](docs/deps.md)
- 上游怎么改：[docs/如何改开源组件.md](docs/如何改开源组件.md)
- PR 规范：[docs/PR规范.md](docs/PR规范.md)
- 答辩口径：[docs/答辩口径.md](docs/答辩口径.md)

## 结构

```
client/    JavaFX + PJSUA2
native/    各平台 pjsua2 预编译（不入库）
server/    docker compose：Kamailio + FreeSWITCH
admin/     Spring Boot 单体
docs/      规划与分工
```

## 怎么跑（阶段 5 完整协同）

1. 启动信令与 MCU 容器（先探测校园网 IP 再启动 Kamailio + FreeSWITCH）：

```bash
cd server
./start-kamailio.sh          # macOS / Linux (自动启用 --profile mcu)
# Windows: .\start-kamailio.ps1
```

2. 启动后台管理与 ESL 监听服务（提供 HTTP 文件存储、CDR 通话统计与房间在会人数同步）：

```bash
cd admin
mvn spring-boot:run
```

3. 启动桌面客户端（默认 TCP、账号 `1001`，第二窗口用 `1002`）：

```bash
cd client
./启动.command
./启动.command --user=1002 --port=5062 --peer=1001
```

SIP 域：`lab`。
- **一对一通话**：`INVITE sip:<user>@lab` 走 Kamailio 路由，两端 RTP 直连，**绝对不进入 MCU**；
- **多人会议室**：`INVITE sip:room-<id>@lab` 由 Kamailio 自动转至 FreeSWITCH MCU（5080 端口），进入 `video-mcu-3x3` 会议室进行 3×3 九宫格 720p30 视频混流与音频混音（最多 8 人，第 9 人拒绝）；
- **后台与 ESL**：后台通过纯 Java 原生 Socket 监听 FreeSWITCH 8021 ESL 端口，自动同步实时在会人数与峰值；
- 原生库不入库，见 `native/`。

## 阶段 6：免环境绿色便携包与双机验收 (最高分交付)

本系统采用 **方案 A（绿色便携包） + 形态 B（单机宿主双机验收）** 架构。

### 1. 一键自动构建交付包

```bash
./scripts/build-dist.sh
```

构建完成后将在 `dist/` 目录下生成：
- **`小组编号-实践阶段二-macOS-arm64.zip`**：macOS 独立便携包（内嵌 40MB 精简 JRE 17，免配置环境、免安装 JDK、免 Maven，双击即开）；
- **`小组编号-实践阶段二-Windows-x64.zip`**：Windows 独立便携包（含 JavaFX 跨平台图形组件与 MinGW 动态链接库）；
- **`小组编号-实践阶段二-完整主机服务.zip`**：电脑 A 主机服务包（含预编译秒开 `sipim-admin.jar` 与 Docker 容器启动脚本）。

### 2. 双机协同验收流程 (形态 B)

- **电脑 A（主机 / Docker 宿主）**：
  1. 解压 `完整主机服务` 包（或在源码根目录）；
  2. 双击运行 `一键启动-主机服务.command`（Windows 为 `.bat`），自动拉起 Kamailio、FreeSWITCH MCU 与 Spring Boot 后台，控制台输出当前局域网 IP（如 `10.21.253.218`）；
  3. 打开客户端（默认以用户 `1001` 登录）。

- **电脑 B（客户端分机 / 老师电脑 / 被叫方）**：
  1. **电脑 B 完全无需安装 Docker、无需安装 JDK、无需 Maven**；
  2. 解压对应的客户端绿色包（macOS 或 Windows）；
  3. 双击 `启动客户端-指定服务器IP.command`（Windows 为 `.bat`），输入电脑 A 的局域网 IP（`10.21.253.218`），用户编号默认 `1002`；
  4. 点击【注册/上线】，与电脑 A 进行一对一视频通话、文字/文件/短视频收发，或直接呼叫 `sip:room-101@lab` 加入 8 人 MCU 多人视频会议！

## 协作

仓库：https://github.com/GaiYa0/software-practise  
在仓库 Settings → Collaborators 里加另外两名同学。不要提交 `.dll` / `.dylib`。改代码走 PR，见 [docs/PR规范.md](docs/PR规范.md)。
