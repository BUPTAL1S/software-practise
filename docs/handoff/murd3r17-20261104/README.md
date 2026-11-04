# 信令与业务服务服务器迁移交付手册

交付人：信令层负责人　接收人：murd3r17　编制日期：2026 年 11 月 4 日

本文用于将 Kamailio、FreeSWITCH 和 Spring Boot HTTP 后台整体迁移到 murd3r17 的服务器，并保留账号、联系人、消息、文件、头像、会议和统计功能。执行顺序为确认环境、复制演练、最终备份、部署校验、客户端切换和验收。旧环境在验收前保留。

当前未提供目标服务器参数，也未在目标服务器部署。本文以 Linux amd64、Docker Engine 与 Compose V2、Java 17、同机部署三项服务为实施方案。完成文档检查不等于完成服务器验收；只有第 10 节全部通过并由双方确认后，才可宣布迁移完成。

## 1 交付基线与执行分工

2026 年 11 月 4 日再次核对，远端 main 为 `9d99af9b2b2992b6f29d39ee988c0a45a742ea99`，包含 `f25dad8` 的通话与信令修复。组员新 PR #58 至 #62 在本次核对时均未合并，不能将其功能视作 main 已具备。逐项影响、分支 SHA 和依赖记录在附件 PR_COMPATIBILITY.md。

交付前必须选择并记录一种基线：直接使用上述 main，则按现有布局和权限验收；若采用组员新功能，则由双方确定完整集成提交，重新构建后台、客户端及原生库，并执行附件中的条件验收。不能将五个独立 PR 的文件随意覆盖在一起，也不能只更新服务端、让客户端继续使用旧包。组员的候选集成分支不是已合并的正式发布版本。

- 信令负责人提供：仓库版本、完整 server 配置、后台源码、客户端对应版本、数据备份、文件校验清单、旧地址和回滚记录。
- murd3r17 负责：服务器信息、Docker 与 Java 环境、磁盘权限、网络与云安全组、按本文部署、日志留存与日常运行。
- 双方共同完成：两台真实客户端验收、最终数据核对、切换时间确认和故障回滚决定。

本交付包包含 README.md、同内容 Word 文档、PR_COMPATIBILITY.md、compose.server.yml、deploy.env.example、sipim-backend.service 和 prepare_database.py。真实数据库、上传文件和密码应通过受控渠道另行传输，不放进代码仓库或公开 PR。`target/`、旧构建 JAR 和本机 `.env` 不作为部署真相。

## 2 murd3r17 必须补齐的服务器信息

逐项填入交接记录后再执行部署：

- 系统发行版与版本：待填；CPU 架构：待填；CPU 核数与内存：待填。
- 云厂商或所在机房：待填；服务器可达 IPv4：待填；固定公网 IPv4 与 NAT 映射情况：待填。
- 客户端所在网络及是否可互通 UDP：待填；公网出口带宽和流量限制：待填。
- SSH 地址、端口、管理来源网段：待填；域名及 A 记录：可选待填。
- 部署时间、停写窗口、负责人联系方式、允许的最大停机时间：双方待定。
- 旧 SIP 地址、旧 HTTP 地址、原后台实际数据库路径、上传目录、头像目录：信令负责人填写。

服务器应有可持久化磁盘。建议以 4 vCPU、8 GiB 内存作为小组演练的起点，该数值是容量规划建议，不是 8 人视频会议的性能承诺；最终以 3 人及 8 人实测 CPU、带宽和画面为准。镜像架构不匹配时停止执行，不能依赖模拟运行作为正式性能验收。

## 3 网络条件与迁移边界

客户端向服务器 5060 发送 SIP；一对一音视频仍在客户端之间直接传 RTP。会议的音视频由 FreeSWITCH 处理，使用服务器 UDP 21000 至 21099。消息历史、文件、联系人和管理员工作台通过 HTTP 后台；ESL 负责会议人数与视频静音控制。

```text
客户端 A 和 B -> Kamailio 5060 -> 对端客户端 SIP
客户端 A <--------------------> 客户端 B RTP
客户端 -> Kamailio -> FreeSWITCH 5080 容器内 SIP
客户端 <------------> 服务器 21000 至 21099 UDP 会议媒体
客户端 -> 后台 8080 -> SQLite 和上传文件
后台 -> 127.0.0.1:8021 -> FreeSWITCH ESL
后台写头像目录 <-> FreeSWITCH 读同一头像目录
```

**必须先判断服务器是否能访问客户端通告的媒体地址。** 当前客户端通过 CampusLan 选择校园网 IP，未配置 ICE、STUN 或 TURN；Kamailio 的 received 与 Contact alias 修复解决信令回程，不会自动解决媒体 NAT。

如果服务器与成员仍处于可互通的校园网，可先沿用现有媒体方案。如果服务器在公网、成员只通告不可路由的 10.x 私网地址，不能只改一个服务器 IP 就宣布迁移可用。此时须先选定并完成客户端媒体 NAT 方案或具有实际可达通告地址的组网方案，再验收。当前 CampusLan 还会跳过多类虚拟及隧道网卡，因此仅安装 VPN 也不能视为已完成客户端适配。未满足该前提时，可做预部署，不进行正式切换。

当前 main 的 Kamailio 不验证 SIP 密码，且部分 HTTP 查询和文件下载按现有兼容策略允许匿名访问。若采用 #58，私有附件下载和预览改为带认证，必须与新版客户端一起交付，不能按旧的匿名下载行为验收；公开头像另有判断。首次迁移应限于经云安全组或网络边界限制的组内网络，不能将现有服务原样向整个互联网开放。若要开放公网，应另行实现并验收 SIP 鉴权、HTTP HTTPS 与访问策略；这不是本文模板已经完成的功能。

端口与访问范围按以下清单配置：

- 5060 TCP 和 UDP：只允许实际成员来源或已批准的受控网络。
- 21000 至 21099 UDP：允许参会客户端实际来源，包含返回流量；云安全组、NAT 和主机转发规则必须一致。
- 8080 TCP：仅组内受控访问。采用 HTTPS 时由反向代理提供 443，并另行验证客户端文件上传和下载。
- 5080 TCP 和 UDP：仅 Compose 内部网络使用；本交付模板不发布到宿主机。
- 8021 TCP：仅发布到服务器 127.0.0.1，后台同机访问；不开放公网。
- SSH：按实际管理端口，只允许管理员来源。不要为迁移关闭整机防火墙。

Docker 发布的端口可能绕过 UFW 的普通规则。必须从另一台机器实测可达与不可达范围，以云安全组及适用于 Docker 的过滤规则共同约束，不能只看 `ufw status`。

若采用 #58，应用单文件上限为 64 MiB、multipart 请求上限为 65 MB，客户端上传超时为 120 秒。反向代理须为 multipart 头和表单开销留余量，并为实际带宽配置上传等待时间；不要把代理上限误配成仅允许 1 MB 或 64 MB 的整次请求。验收包含接近上限的正常文件和明确拒绝超限文件。

## 4 必须交付的数据和配置

传输前记录 Git 完整 SHA、两个容器镜像的 RepoDigest、后台 JAR 的 SHA256，以及数据清单。仓库里的镜像标签为 Kamailio 5.8.3 alpine 和 FreeSWITCH 1.11.3；演练通过后锁定所用 digest，正式切换时不顺便升级镜像或依赖。

必须复制的内容：

- `server/kamailio/`：kamailio.cfg 等配置；public-ip.defs 必须在新环境重生成。
- `server/freeswitch/`：vars、autoload_configs、sip_profiles、dialplan 和 sounds；会议头像来自实际运行目录，需另外备份。采用 #61 时必须同时包含 conference.conf.xml 和新增的 conference_layouts.conf.xml。
- 后台实际 SQLite：默认是 `admin/sipim.db`，也可能由 SIPIM_DB 覆盖，必须以旧进程配置为准。
- 实际 uploads：默认 `admin/uploads/`，同时核对 files.path 所指文件及头像上传目录是否另有配置。
- 实际 FreeSWITCH 头像：默认 `server/freeswitch/avatars/`，包括 default.png 及成员头像。
- 对应客户端：Java 源码版本及匹配原生依赖。当前 main 的窗口库交付方式不可套用于 #60/#62：采用这些 PR 时，须从所选集成版本重建 PJSUA2、callwindow、winmirror 三个 DLL，核对两个辅助库 ABI 2 及释放、延迟、视口三项补丁；不能从旧 main 拿一个 DLL 混入新包。

SQLite 保留 users、rooms、files、cdr、private_messages 和 room_messages 等实际表。不要导出后重新注册账号，不要改数据库 ID、SIP ID、密码哈希或用测试库替换正式库。不要执行删库重新初始化；空库会触发演示用户初始化。

如果采用 #58，还应只读检查历史 username 和 sip_id 是否满足新 IdentityRules，是否存在保留名或不安全路径字符；发现不兼容数据先列清单交负责人处理，不在迁移脚本里自动重命名。该 PR 的相关改动未新增业务实体表，不能因此重建账号或凭空添加数据库迁移。

Kamailio 的注册表和 HTTP 登录 token 存在内存中，不作为迁移文件复制。切换后成员需要重新登录和注册。正在进行的通话和会议不能跨进程无缝搬迁，应先正常结束。

## 5 在旧机制作一致的备份

先用副本演练，正式切换时再做一轮最终备份。最终备份前通知成员结束通话并停写，正常停止旧后台，确认其进程已退出。只处理已识别的项目进程，不结束所有 java.exe。

在实际备份文件上执行 SQLite backup API。下面示例在旧仓库根目录运行，要求系统已有 Python 3，且已确认真实数据库就是默认路径。`delivery-data` 必须是本次新建的目录；若存在同名备份，改用新的交付目录，不覆盖旧备份。

```powershell
New-Item -ItemType Directory -Path delivery-data -ErrorAction Stop
python -c "import sqlite3,pathlib; s=pathlib.Path('admin/sipim.db').resolve(); assert s.is_file(); a=sqlite3.connect(s.as_uri()+'?mode=ro',uri=True); b=sqlite3.connect('delivery-data/sipim.db'); a.backup(b); assert b.execute('PRAGMA integrity_check').fetchone()[0]=='ok'; b.close(); a.close()"
Copy-Item -LiteralPath admin/uploads -Destination delivery-data/uploads -Recurse
Copy-Item -LiteralPath server/freeswitch/avatars -Destination delivery-data/avatars -Recurse
Get-ChildItem delivery-data -File -Recurse |
  Get-FileHash -Algorithm SHA256 |
  Export-Csv delivery-data/SHA256.csv -NoTypeInformation -Encoding utf8
```

如果旧环境的上传或头像目录自定义过，以上 Copy-Item 必须替换为真实目录。缺文件时先补齐，不能复制一个空目录冒充完成。生产库可能有 WAL 和 SHM 文件，不在运行中仅复制主 .db，也不随意删除 WAL；使用一致的备份接口并保持文件备份期间停写。

备份交接记录至少包括六张业务表行数、用户与 SIP ID 清单、文件数量及哈希、备份时间、旧 Git SHA。核对已删除的真实用户不会在目标重新出现。不在本次操作中删除或更改其他成员账号。

## 6 新服务器预部署

以下服务器命令在已授权的管理员 root shell 中执行，可先运行 `sudo -i`。先安装 Docker Engine 与 Compose V2，按文末官方链接选择匹配发行版的步骤；安装 Java 17、Maven、Git、Python 3、SQLite 命令行及 curl。Ubuntu 或 Debian 的软件包名称可用以下方式检查和安装，其他系统须先调整。

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk maven git python3 sqlite3 curl openssl
docker version
docker compose version
java -version
uname -m
```

在 `/srv/sipim/repo` 检出双方约定的完整 SHA。不要直接运行仓库原有 start-kamailio 脚本：它会自动探测校园内网地址并覆盖 PUBLIC_IP，不适合作为未知云服务器的部署入口。

```bash
sudo install -d /srv/sipim/{repo,app,data,runtime,handoff,backups}
# 由有仓库访问权限的账号拉取，令牌不写进命令历史或交付文档。
git clone https://github.com/BUPTAL1S/software-practise.git /srv/sipim/repo
cd /srv/sipim/repo
git checkout FULL_APPROVED_COMMIT_SHA
git merge-base --is-ancestor f25dad8 HEAD
git rev-parse HEAD
```

如果代码已经存在，不重复 clone 到非空目录。FULL_APPROVED_COMMIT_SHA 是占位值，执行前必须换成实际 SHA；若祖先检查失败，先核对修复是否以其他提交等效合入，不能直接跳过确认。

将交付包附件复制到 `/srv/sipim/handoff/`，创建后台运行账号及目录。已有账号则先核对身份，不重复创建。

```bash
sudo useradd --system --user-group --home /srv/sipim --no-create-home --shell /usr/sbin/nologin sipim
sudo install -d -o sipim -g sipim /srv/sipim/data/{uploads,avatars}
sudo install -d -m 750 /etc/sipim
sudo cp /srv/sipim/handoff/deploy.env.example /etc/sipim/deploy.env
sudo chown root:sipim /etc/sipim/deploy.env
sudo chmod 640 /etc/sipim/deploy.env
```

编辑 `/etc/sipim/deploy.env`：PUBLIC_IP 必须填客户端实际可达的 IPv4，不填域名、不填任意探测出的容器 IP。域名可供客户端连接，但 SIP 与 SDP 使用这里确认的 IPv4。ESL_PASSWORD 改成独立随机十六进制密码，可用 `openssl rand -hex 32` 生成，不沿用示例默认口令。文件其余路径与本手册一致。

加载配置并从仓库复制运行配置。此处只在新服务器的新运行目录执行，不修改旧环境。

```bash
set -a
. /etc/sipim/deploy.env
set +a
cp -a /srv/sipim/repo/server/kamailio /srv/sipim/runtime/
cp -a /srv/sipim/repo/server/freeswitch /srv/sipim/runtime/
mkdir -p /srv/sipim/runtime/freeswitch/avatars
sudo chown root:sipim /srv/sipim/runtime
sudo chmod 750 /srv/sipim/runtime
python3 - <<'PY'
import os, ipaddress, xml.etree.ElementTree as ET
ip = ipaddress.IPv4Address(os.environ['PUBLIC_IP'])
assert not ip.is_loopback and not ip.is_unspecified and not ip.is_multicast
secret = os.environ['ESL_PASSWORD']
assert len(secret) >= 32 and all(c in '0123456789abcdefABCDEF' for c in secret)
p = '/srv/sipim/runtime/freeswitch/autoload_configs/event_socket.conf.xml'
t = ET.parse(p)
for node in t.iter('param'):
    if node.get('name') == 'password': node.set('value', secret)
t.write(p, encoding='utf-8', xml_declaration=True)
PY
```

本手册采用独立 compose.server.yml，不能与原 server/docker-compose.yml 合并使用。后者会重新加入不需要的宿主机端口映射。定义命令简写后，创建但暂不启动容器，以得到真实 Docker 网关。

```bash
dc() {
  docker compose --env-file /etc/sipim/deploy.env \
    -f /srv/sipim/handoff/compose.server.yml "$@"
}
dc config --quiet
dc pull
dc create
GW=$(docker network inspect sipim-server_default \
  --format '{{(index .IPAM.Config 0).Gateway}}')
test -n "$GW"
printf '#!substdef "!PUBLIC_IP!%s!g"\n#!substdef "!GATEWAY_IP!%s!g"\n' \
  "$PUBLIC_IP" "$GW" > /srv/sipim/runtime/kamailio/public-ip.defs
dc run --rm --no-deps kamailio -c -f /etc/kamailio/kamailio.cfg
```

ESL XML 的密码与 deploy.env 必须一致。模板已替换 FreeSWITCH 健康检查，避免更换 ESL 密码后仍用旧口令检查而误报 unhealthy。docker compose config 完整输出含环境秘密，日常用 --quiet，不将展开后的配置发到群里。

## 7 导入数据库与历史文件

将 delivery-data 中的备份传到 `/srv/sipim/incoming/`，先核对传输前后的哈希。uploads 和 avatars 均须完整复制到 `/srv/sipim/data/` 对应目录。不要先启动后台，再覆盖正在写入的数据库。

Windows 数据库的 files.path 可能保存 `uploads\...` 或旧机绝对路径，Linux 无法按原路径打开。附件 prepare_database.py 只从离线备份生成一个新的目标库，逐项核对附件存在和大小，把 files.path 改成 `/srv/sipim/data/uploads/文件名`；不修改源备份、消息内容、账号、ID 和密码哈希。

```bash
cp -a /srv/sipim/incoming/uploads/. /srv/sipim/data/uploads/
cp -a /srv/sipim/incoming/avatars/. /srv/sipim/data/avatars/
python3 /srv/sipim/handoff/prepare_database.py \
  --source /srv/sipim/incoming/sipim.db \
  --target /srv/sipim/data/sipim.db \
  --uploads /srv/sipim/data/uploads
sudo chown -R sipim:sipim /srv/sipim/data
sudo chmod 750 /srv/sipim/data /srv/sipim/data/uploads
sudo chmod 755 /srv/sipim/data/avatars
sudo find /srv/sipim/data/avatars -type f -exec chmod 644 {} +
sqlite3 /srv/sipim/data/sipim.db 'PRAGMA integrity_check;'
sqlite3 /srv/sipim/data/sipim.db 'PRAGMA foreign_key_check;'
```

目标 sipim.db 已存在时脚本会停止；空用户表或空房间表也会停止，因为后台会自动初始化演示数据，不能无条件满足“启动后行数不变”。若源系统确实允许无房间，双方须先决定初始化策略，不能由迁移脚本自行造房间。演练库先另行归档，经确认后为最终切换指定新目录或移走演练库，不能自动覆盖。脚本出现缺文件、同名冲突或大小不符就停止部署，找回正确文件后重试。头像目录设置为可遍历，供容器内 FreeSWITCH 用户读取；不要将整个 uploads 目录一并放宽。

此版本客户端对包含 `/api/files/` 的历史下载 URL 会重定位到当前 HTTP 后台，因此不要对聊天正文做全库字符串替换。仍需抽查旧私聊文件、群文件和头像实际下载；外部网站 URL 不属于本系统迁移范围。

## 8 构建后台并启动服务

从指定提交源码重新构建，禁止使用仓库中旧日期的 target JAR。下面测试使用项目测试配置；如环境缺依赖，先解决构建问题，不用跳过测试来宣布可交付。

```bash
cd /srv/sipim/repo
mvn -f admin/pom.xml clean package
sudo install -m 644 admin/target/sipim-admin-0.1.0-SNAPSHOT.jar \
  /srv/sipim/app/sipim-admin.jar
sha256sum /srv/sipim/app/sipim-admin.jar
sudo cp /srv/sipim/handoff/sipim-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
dc up -d
dc ps
```

先等 FreeSWITCH 健康检查通过，再启动后台。若提示 ACL 拒绝 ESL，检查真实 Docker 来源网段是否在 runtime/freeswitch/autoload_configs/acl.conf.xml 的允许范围；只添加实际专用网段，不用全网允许来消除报错。

```bash
dc exec -T freeswitch sh -ec \
  'fs_cli -H 127.0.0.1 -P 8021 -p "$ESL_PASSWORD" -x "status"'
sudo systemctl enable --now sipim-backend
curl --fail --silent http://127.0.0.1:8080/api/health
sudo systemctl status sipim-backend --no-pager
```

HTTP 健康响应必须同时满足 status 为 ok、esl.enabled 为 true、esl.connected 为 true。ESL 目标应是服务器本机回环 8021。后台应能写 SQLite、uploads 与 avatars；FreeSWITCH 可读同一 avatars 目录。只看到 8080 开放不能算会议控制正常。

首次启动会检查并生成默认头像，ESL 完整快照会校正会议人数。按实际业务表行数和成员清单核对，不能只看登录页是否出现。记录容器状态、镜像 digest、后台日志和构建 SHA。

## 9 正式停写与客户端切换

演练验收后约定停写窗口。结束所有真实通话和会议，停止旧后台写入并做第 5 节最终备份。停止新后台，归档演练数据，用最终备份重复第 7 节转换与核对，再启动新后台。禁止旧、新后台同时接收真实写入。

每个客户端退出并重新启动，明确指定新 SIP 与新 HTTP 地址；SIP 域仍为 lab，用户和 SIP ID 不变，优先 TCP。本机 SIP 端口是客户端自己的监听端口，不能填成服务器端口或服务器 IP。不要点“填入本机 IP”把新服务器地址覆盖回本机。

PowerShell 源码客户端示例，先把 NEW_SERVER_IPV4 和账号替换为真实值：

```powershell
cd client
mvn javafx:run "-Djavafx.args=--user=1001 --server=NEW_SERVER_IPV4 --http=http://NEW_SERVER_IPV4:8080 --domain=lab --transport=tcp --port=5062"
```

若使用已验收的 HTTPS 域名，将 --http 完整替换为该 HTTPS 基址。所有成员使用同一交付版本及正确 DLL，不仅更新信令负责人本机。登录界面的连接设置也应显示新地址。采用 #60/#62 时，源码客户端先按该提交的原生构建说明重建；便携包须运行 verify-windows-package.ps1，保存 build-id、manifest 及 collect-diagnostics.bat 的结果。包验证中的 8 个演示用户和 ESL 禁用仅适用于隔离测试库，不是正式库预期行数或服务器会议验收证据。

旧客户端可能探测到本机 5060 而优先使用旧 SIP 服务；HTTP 探测失败也可能回退到旧 localhost 后台。正式切换时停止旧本机服务或确认其不能接收业务，使用显式 --server 与 --http，并核对实际登录后的地址。所有成员重新登录、重新注册，旧 token 与注册状态不复用。

切换采用计划停机，不承诺正在进行的通话无缝迁移。直到双方签署验收，旧代码、配置和最终备份均保留，不删旧机数据。

## 10 功能保持验收清单

每项填写执行人、时间、客户端版本、实际结果和证据路径。至少两台真实 Windows、不同账号参与；跨网络部署必须包含实际成员网络。以前的模拟测试不能代替本次目标服务器验收。

- A01 数据一致：六张业务表行数与最终备份一致，账号及角色不变，已删除成员未恢复；文件清单与哈希一致。
- A02 登录与联系人：管理员和普通成员登录成功；列表和在线状态更新；退出后回到登录页，再登录可用。
- A03 消息：双向文字、时间及年月日分隔显示正确；重启后历史仍在；离线成员重新登录能收到待处理消息。采用 #58 时增加连续分页无丢失、注销后后台任务停止、会议心跳与消息请求互不阻塞的验证。
- A04 文件：新上传并下载普通文件和图片；抽查迁移前的私聊及房间附件，校验下载内容；头像上传后客户端和 MCU 均更新。采用 #58 时，验证收发双方带认证访问、无关普通账号 403、匿名私有附件 401、发布头像按其公开策略访问；确认新版图片预览带认证，并测试较大文件及超限拒绝。
- A05 语音：双方各主动呼叫 10 次，均能振铃、接听、双向听见；分别由主叫和被叫挂断，另一端同步结束。
- A06 视频：双方各主动呼叫 10 次，双方真实画面与声音正常；持续通话至少 10 分钟；摄像头开关 20 轮，无闪退、卡死或设备无法重开。采用 #59 时增加语音升视频同意与拒绝两条路径，拒绝后语音继续、通话计时保留、旧会话状态消息不影响新会话；采用 #60 时增加最大化、持续缩放、对端关摄像头后本地 PiP 仍在正确位置，以及镜像只影响本地预览。
- A07 会议：至少 3 人视频参会，测试语音参会、静音、摄像头、头像和反复离会重入。当前 main 验固定 3×3；采用 #61 后改验 group:sip-adaptive 按视频成员数选择 1、2、4、9 格，覆盖增减人数、纯语音成员及摄像头关闭。保持历史 profile 名 video-mcu-3x3、960×540、15fps、1mb 和 8 人上限，不能因 profile 名仍含 3x3 就认定新布局未生效。更新两个 XML 后用新建会议验收，不依赖现有会议 reloadxml。
- A08 容量与人数：条件允许时验 8 人与第 9 人限制；离会后人数回落，成员客户端异常退出后人数按媒体超时与快照校正，统计不长期残留。后台可能约 5 秒对账，断流超时配置约 60 秒，不要求异常退出瞬间归零。
- A09 管理与统计：管理员工作台正常，CDR 与会议统计更新。删除同步只用经批准的专用临时测试账号，不能删除其他真实成员。
- A10 重启恢复：在无真实会议时重启后台及信令服务，确认数据仍在、客户端可重新登录和注册；服务器重启后服务自动恢复。
- A11 网络边界：外部未授权机器不能访问 8021、5080；成员能访问规定的 SIP、HTTP 与会议 UDP。只测 TCP 端口通不算媒体验收。

任一核心项失败不宣布迁移完成。A05 至 A08 暂无设备或人员时明确记“未验收”，保持试运行或回滚，不填“通过”。

## 11 常见故障定位

注册成功但没有声音或画面：先看 INVITE 与 200 OK 的 SDP c 地址、音视频 m 端口以及双方 RTP 收发，检查私网地址、UDP、防火墙和 NAT；不能用 SIP 200 成功替代媒体检查，也不先靠降码率解决地址不可达。

一方挂断另一方不结束：按同一 Call-ID 查看 Kamailio 的 ACK、BYE、Record-Route 与 Contact alias，确认走新服务器和正确注册连接。不要直接删除回程修复或把 domain 改成新服务器 IP。

会议画面正常但人数或摄像头控制不更新：查看 /api/health 的 esl.connected、ESL 密码、ACL 和后台事件日志；不要仅重启客户端。

旧文件 404 或头像丢失：检查 files.path 是否已转换、实际文件是否齐全、后端目录权限及头像双端挂载；不要重建用户或重新灌测试数据。

客户端闪退：保存对应构建 SHA、JDK、pjsua2.dll 与 callwindow.dll 哈希、两端日志、Windows 崩溃记录及 Call-ID。旧版曾出现未注册线程断言和引擎销毁后清理通话的断言；新故障应对照实际堆栈，不直接归因服务器迁移。

常用只读命令：

```bash
dc logs --tail 200 kamailio
dc logs --tail 200 freeswitch
sudo journalctl -u sipim-backend --since '-15 minutes' --no-pager
dc exec -T kamailio kamcmd ul.dump
dc exec -T freeswitch sh -ec \
  'fs_cli -H 127.0.0.1 -P 8021 -p "$ESL_PASSWORD" -x "conference xml_list"'
```

日志可能含手机号样式的 SIP ID、IP、消息与鉴权信息，对外提供前脱敏。新 SSH 会话需重新定义 dc 函数；修改 FreeSWITCH 主机模板后通常要重建或重启容器才会复制到 /etc/freeswitch，reloadxml 不会自动复制宿主机文件。活动会议期间不做此操作。

## 12 回滚和日常交接

发生核心登录失败、双向媒体失败、持续闪退、数据或附件缺失时，停止新环境真实写入，保留日志。先归档新数据库、uploads、avatars 与配置，再决定恢复旧环境。预定回滚时限由双方在第 2 节填写。

```bash
sudo systemctl stop sipim-backend
dc stop
```

旧机按原记录的版本、数据目录和地址恢复服务；成员显式切回旧 SIP 与旧 HTTP 地址，并重新登录注册。若新环境已经产生真实消息、文件或账号变更，不可直接以旧备份覆盖；须先保持两边停写，核对增量数据并由负责人决定合并方式。SQLite 文件不能靠覆盖来合并。

稳定运行且双方确认后，再停用旧机入口。murd3r17 后续至少维护：定期一致备份数据库及附件、磁盘余量、容器日志轮转、ESL 连接、服务自启动和镜像版本清单。更新配置或镜像前保留一份可恢复快照。

最终交接记录应填写：目标系统与 IP、Git SHA、镜像 digest、JAR 哈希、数据清单哈希、实际地址、启停命令、备份位置、每项验收结果和未完成项。交付人确认：待签；murd3r17 确认：待签；确认日期：待填。

## 13 仓库依据与官方参考

仓库依据为 main 基线和 PR_COMPATIBILITY.md 中记录的五个候选 PR 实际 diff，包括 server 配置、后台文件与会话实现、客户端调用及构建脚本。旧 README 中部分参数为历史说明；960×540 和 15fps 按实际 XML 核对，固定或自适应布局依所选交付提交决定。

- Docker 安装：https://docs.docker.com/engine/install/
- Docker 发布端口与防火墙：https://docs.docker.com/engine/network/packet-filtering-firewalls/
- Compose 多文件合并规则：https://docs.docker.com/reference/compose-file/merge/
- SQLite 在线备份：https://sqlite.org/backup.html
- SQLite WAL：https://sqlite.org/wal.html
- PJSIP 媒体 NAT：https://docs.pjsip.org/en/2.14/specific-guides/network_nat/nat_guide.html
- FreeSWITCH ESL：https://developer.signalwire.com/freeswitch/integration/event-socket/
