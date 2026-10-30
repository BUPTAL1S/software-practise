# 开发准备与本轮离线优化状态

2026-10-30 更新：PR49 已修复并于 17:42:39 合入 main（ca65897c34aa074c34fb9630e28dbf67ef929ca1）。此后用户将本轮范围调整为离线音视频性能优化、后台修复和未完成功能，尽量不动前端，明确排除双机反复通话与入会验收；同学服务器故障期间不部署或修改其 Docker。

本轮已清零客户端原三条失败，完成媒体档位、能力缓存、非阻塞诊断、ESL 字节帧及批量统计优化，并通过 Windows 便携包内 JRE 的隔离启动验证。完整后台 93 项（92 通过、1 跳过）、客户端 92 项（83 通过、9 跳过）、浏览器 10 项通过；真实媒体体验和 macOS 包不在本轮验证结论内。详情见 [优化报告](reports/media-backend-optimization-20261030.md)。以下初始审查快照与后续真机要求保留作追溯，不能当成本轮仍需执行的任务。

延迟追加范围已完成：音视频接收缓冲分开，重建原生库启用低延迟/最新画面策略，修复 15fps 乱序组帧下标错误。24 组各 1000 帧的合成接收验证通过；15fps 滞后为 66.667ms、25fps 为 80ms，均不等于真实通话端到端延迟，见 [延迟报告](reports/media-latency-20261030.md)。

## 基线与协作边界

- 仓库：`D:/work/software-practise-upstream`。
- 初始准备基线：`6c91ecb8dc1a7eb103186addeeb1358f5d6df1dd`。当前本地与远端 main 均已同步到 `ca65897c34aa074c34fb9630e28dbf67ef929ca1`。
- [PR49](https://github.com/BUPTAL1S/software-practise/pull/49) 初始审查 head 为 `11d76cf034e089d6d036da2c8db6949c57bacb70`；修复 head 为 `6356192894279740a2942c51448fc6cd12fa81a6`。已由 murd3r17 按用户指令以 merge commit 合并，合并时开放 PR 数为 0。
- 初始准备文档保存在 `docs/development-readiness-20261030`，原工作区保持原状。本轮实现位于角色分支 `admin/phase6-readiness-20261030`，工作副本 `D:/work/.codex-execution/software-practise-phase6-20261030`，从上述 main 创建。
- 审查与探针在 `D:/work/.codex-audits/software-practise-20261030/` 的独立检出中执行；没有启动项目后台、JavaFX 客户端或容器，也没有重启既有服务。
- 按 [PR规范](PR规范.md) 从最新 main 建角色分支。跨角色接口需对应角色对齐；整体规划和分工接口表保留原状。本次仅向既有 PR 推送必要修复并按用户指令合并，没有发布评审或评论。

PR 有新提交或 main 发生变化时，重新比较基线并复核受影响结论。原 `v0.9-call-stability` 标签在本地提交图中已包含近期稳定性提交，其后到初始基线 6c91ecb 的提交数为 3；不要沿用旧交接中“50 个提交”的描述。

## 当前实现与文档差异

以下描述的是基线代码行为，后续需要同步到需求和报告，涉及接口约定的部分按协作规范处理。

| 主题 | 当前代码 | 需要同步的说明 |
| --- | --- | --- |
| 一对一通话 | Kamailio 查位置，客户端之间媒体直连 | 继续保持一对一不进 MCU |
| 房间呼叫 | room-* 转 FreeSWITCH，3×3、上限 8 人 | 固定容量；第 9 人拒绝仍需真机验证 |
| 房间文字和文件 | SIP MESSAGE 对房间返回 405；客户端另走 HTTP 房间消息 | “没有群聊”已经过时，须区分 SIP 与 HTTP |
| 私聊 | SIP 在线送达，HTTP 持久化及离线收件箱 | 需求文档仍把离线消息列为不做 |
| 账号 | 自助注册、昵称、改密码和头像已有实现 | HTTP 与 SIP 鉴权边界仍分离 |
| ESL | 事件监听、快照核对、视频静音、头像替换、重复及过期成员清理 | 已超出仅监听人数的早期描述 |
| 视频参数 | 默认 balanced 为 848×480/25fps、1.2Mbps，上限 1.8Mbps；legacy 保留旧档；MCU 仍为 960×540/15fps/1mb | README 已修正；发送目标不等于实测帧率 |
| 原生库 | pjsua2.dll 不入库，既有视频补丁归档至 native/windows-x64/patches | 原生参数读回和当前补丁反向 dry-run 通过；延迟补充已在独立 2.17 检出重编 DLL |

主要入口：[客户端消息与应用壳](../client/src/main/java/lab/sip/client/MainApp.java)、[通话协调](../client/src/main/java/lab/sip/client/CallUiCoordinator.java)、[SIP 封装](../client/src/main/java/lab/sip/client/SipService.java)、[HTTP 房间](../admin/src/main/java/com/lab/sipim/web/RoomController.java)、[私聊](../admin/src/main/java/com/lab/sipim/web/PrivateMessageController.java)、[ESL](../admin/src/main/java/com/lab/sipim/esl/EslEventListener.java)。

## 验证基线

| 对象 | 总数 | 通过 | 失败 | 错误 | 跳过 | 证据范围 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 初始 main 后台现存报告 | 63 | 62 | 0 | 0 | 1 | 2026-10-30 16:12 XML，本轮未重跑 |
| 初始 main 客户端现存报告 | 78 | 67 | 3 | 0 | 8 | 2026-10-30 16:11 XML，本轮未重跑 |
| PR49 原有后台测试 | 70 | 69 | 0 | 0 | 1 | 原审查 head 的隔离验证 |
| PR49 修复后后台测试 | 79 | 78 | 0 | 0 | 1 | 合并前 head 6356192；含新增 9 项权限回归 |
| PR49 修复后浏览器测试 | 10 | 10 | 0 | 0 | 0 | Edge 无界面、随机端口假接口；含父测试和 9 个子测试 |
| 本轮最终后台 | 93 | 92 | 0 | 0 | 1 | 18:34:38 完整 Maven 回归；真实 MCU 项跳过 |
| 本轮最终客户端 | 92 | 83 | 0 | 0 | 9 | 延迟补充后完整 Maven 回归；硬件/显式原生验证开关关闭 |
| 本轮浏览器 | 10 | 10 | 0 | 0 | 0 | 已等待标签独立请求结束，再统计轮询；无生产网页改动 |
| CDR 双端上报探针 main | 2 | 2 | 0 | 0 | 0 | 本轮 MockMvc，专用数据库 |
| CDR 双端上报探针 PR49 | 2 | 1 | 1 | 0 | 0 | 被叫上报预期 200，实际 403 |

原审查的 70 项与 2 项探针分开执行；修复后另执行 79 项后台测试，新增 9 项权限回归全部通过。浏览器验证不包含原生全屏、真实窗口缩放、JavaFX 视频、双机 RTP 或 MCU 验收。详细命令、源码和日志见 [PR49 审查记录](reviews/pr49-review-20261030.md)。

## 第一项 PR49 兼容性修复已完成

责任范围：后台实现，客户端参与接口复核。

**完成状态：** 6356192 已允许通话双方上报，同时校验已有 Call-ID 的记录参与者。9 项新回归、完整后台测试和隔离浏览器测试均通过；修复已随 PR49 合入 main。

**明确问题：** 1003 呼叫 1002 后，被叫客户端以 1002 的 token 上报 `caller=1003, callee_or_room=1002`。现有客户端会在两端上报，PR49 新增的只匹配 caller 的校验将被叫请求拒绝为 403。

**修复与验收要求：**

- 保持真实主被叫方向；允许合法通话参与者上报，不能把被叫的 caller 改成自己来绕过校验。
- 主叫、被叫、无关第三人、未登录和过期会话分别覆盖；无关第三人不得覆盖已有 Call-ID 的记录。
- 同一 Call-ID 双端上报仍只保留一条，时长取有效较大值；连续短通话与反向回拨保持区分。
- 将本轮探针迁入正常测试并扩展权限用例；重跑 PR 原有后台及浏览器验证。
- 补普通用户被叫场景的客户端契约检查。原有测试全过不替代这项回归。

修复提交为 6356192，合并提交为 ca65897。若后续出现回归，应基于具体提交使用正常回退 PR；本次合并不代表真实摄像头、MCU 或双机验收通过。

## 第二项 整理客户端三条失败

已完成。头像测试默认只访问随机回环端口假服务，vmute mock 返回 success=true，摄像头提示使用纯 UI 缓存状态更新；JavaFX 异常回传且等待释放在 finally 中，第二次开关等待异步工作完成。截图全部写 target/test-snapshots。下表保留初始问题。

责任范围：客户端；涉及后台响应格式时与后台对齐。

| 用例 | 已定位情况 | 开发验收要求 |
| --- | --- | --- |
| RealUserWorkflowTest 摄像头按钮 | 主界面仍定义“摄像头已禁用”；开关协调路径未及时刷新预览按钮，旧报告同时包含 JavaFX 断言和外层等待超时 | 修复状态刷新链路；不要仅改断言。同步刷新纯 UI 状态，不在持有原生服务锁时重入；测试须把 FX 异常传回并在 finally 释放等待 |
| AvatarFeatureTest.testToggleRoomVmute | 本地 mock 返回 status=ok；客户端要求 success=true | 对齐 mock 与真实响应；覆盖成功、success=false 和 HTTP 失败，不依赖 ESL |
| AvatarFeatureTest.testLiveBackendAvatarWorkflow | 自动探测真实后台并修改 1001 头像，找不到时回退 mock；实际 vmute 还要求会议成员存在 | 默认测试只用隔离 mock；真实联调显式开启，专用账户、目录和会议，清理放 finally，禁止自动碰运行中的业务后台 |

验收分为离线、原生摄像头、真实后台和 MCU 四类，分别报告分母、失败和跳过原因。默认 Maven 测试不得修改真实头像、账号或会议。该阶段完成前避免直接运行整个客户端测试集。

## 第三项 对齐本机联调环境

责任范围：信令与后台；客户端使用明确的连接参数。

最初审查时的只读快照（非当前联调已恢复的证明）：

| 项目 | 实际状态 | 恢复准备 |
| --- | --- | --- |
| WSL 与 Docker | Ubuntu-24.04 运行，Docker Server 29.1.3；Windows PATH 没有 docker | 使用 WSL 中的 CLI，记录本轮创建的进程和容器 |
| Kamailio | 5060/TCP 可达；镜像 kamailio/kamailio-ci:5.5.2-alpine | Compose 声明 5.8.3；替换前记录旧容器镜像、挂载、网络和恢复方式 |
| 旧容器归属 | 配置挂载真实仓库；没有 Compose 项目标签 | 不直接假定 compose up 能接管同名 kamailio 容器 |
| FreeSWITCH | 无运行容器，5080/8021 不通 | 使用固定 1.11.3 与 mcu profile；ESL 继续仅发布到宿主回环 |
| 后台 | 8080 不通；8088 返回“AI视频安全生产分析系统”的 HTML | 8088 属于其他系统，禁止作为本项目健康成功或停止对象 |
| WLAN 与服务配置 | 当前 10.21.247.131；PUBLIC_IP 仍为 10.21.187.179 | 改配置前备份；以恢复时重新探测的地址为准 |
| 客户端偏好 | SIP/HTTP 均指向同学服务器 10.21.233.158 | 本机联调时显式指定 127.0.0.1 和本项目 HTTP 基址 |
| shell 换行 | server 与 scripts 的已检脚本工作区副本为 CRLF | WSL 直接执行报 pipefail 参数错误；先恢复 LF。server 已有 eol=lf 规则，scripts 尚缺该规则 |

仅在管道中移除 CR 后，IP 探测返回 10.21.247.131；这证明探测逻辑本轮能取到正确地址，不代表启动脚本已经可执行。本轮没有改写换行、配置或运行环境。

恢复顺序：

1. 保存本机 .env、public-ip.defs、客户端偏好，以及旧容器 inspect 结果和必要日志；先确认旧容器替换与回退方式。
2. 修正工作区 shell 换行并补齐脚本规则。核对 WSL 下探测地址确为 Windows 的可达 WLAN 地址。
3. 使用 WSL CLI 启用 mcu profile；对齐声明镜像与实际运行镜像，再由启动流程写入正确地址和网关。
4. 在明确空闲端口启动后台，健康检查必须是 JSON，且 status=ok、esl.connected=true。
5. 本机客户端使用 127.0.0.1，另一台电脑使用宿主校园网 IP；域始终为 lab。确认媒体 UDP 21000–21099 可达。
6. 如需常驻 WSL，只记录并管理本轮启动的会话。停止时按本轮 PID、启动时间或容器 ID 识别，不按端口或进程名批量停止。

恢复后的门槛是 5060/5080/8021 端口、真实后台健康和实际注册均通过；端口打开不能替代 SIP 或 RTP 验收。

## 第四项 修正打包与原生库复现链路

责任范围：客户端打包，后台和信令核对主机包。

初始 [构建脚本](../scripts/build-dist.sh) 有以下准备缺口：

- Windows JRE 只在 scripts/cache/win-jre.zip 已存在时解压，缺失时不会立即中止；本机缺这份缓存。
- 主机包固定拷贝 macOS 原生库和 MAC_DIR 下的 JRE，却同时生成 Windows .bat，需按平台组装。
- 本机没有 macOS PJSUA2 动态库，也没有 dist 目录。
- 已有 JAR 会按文件存在性复用，缺少与源码版本的一致性校验；缺少部分原生材料也可能继续组包。
- scripts 下 shell 文件需固定 LF；Windows 上生成的“macOS JRE”必须核验真实平台，不能仅按目录名判断。
- 主机 A 仍需要可用的 Docker 与指定镜像；分机 B 才是不需要 JDK、Maven、Docker 的交付目标。

开始打包开发前确定：两平台所需 JRE、JavaFX 分类、JNI 与依赖清单；输入缺失应失败；产物记录源码提交、平台、原生库和 ZIP 的 SHA256；打包过程不得夹带 .env、偏好、业务数据库或测试账号状态。发布验收必须使用修复后的新包。

本机原生材料指纹：

| 材料 | 大小或位置 | SHA256 |
| --- | --- | --- |
| pjsua2.dll | 25,416,627 字节，本机忽略文件 | e19935eb972d3bbe55dd29fe30820184ff66c0ddfd7c4b8f6a54857ae9c2610a |
| callwindow.dll | 53,636 字节，仓库已跟踪 | 5d167b4a564943dc0323f3f08dc3d5600c67e1e794e809dfb8afd4ffc0ff7ae1 |
| 视频补丁 | C:/Users/murd3r17/src/pjproject-video-hangup.patch | 49e0fef86d1da9c9840160381253014e5fb84b23df27e78d0326e68e6401a7f6 |

补丁现已归档至 native/windows-x64/patches/video-hangup-2.17.patch，SHA256 不变；对本机源码 5a457451fa2712ba18e12b01738e8ff3af2b26fd 的反向 dry-run 通过。它涉及 dshow_dev.c、sdl_dev.c、vid_port.c、videodev.c、pjsua_vid.c；文件指纹和 dry-run 不能独立证明 DLL 由该补丁构建。改 CallWindowWin.c 时仍须同步重编已跟踪的 callwindow.dll。

本轮新增 [Windows 构建](../scripts/build-windows.ps1) 和 [包验证](../scripts/verify-windows-package.ps1)：验证真实平台、重建 JAR、检查必需 DLL、生成具备后台所需标准模块的包内 JRE，并生成哈希清单；清除开发 PATH 后，JNI 与隔离后台登录/用户/房间/统计读回通过。旧 Bash 构建现在只接受 macOS arm64，且缺 Windows JRE 缓存时提前失败；本轮没有构建或运行 macOS 包。

## 第五项 后续双机验收要求（本轮排除）

责任范围：三角色共同验收，各自保留对应证据。

- 使用明确提交及 SHA256 的新包，在 Windows x64 与 macOS arm64 分别验证包内 JRE、JavaFX、JNI 加载与真实启动。脱机环境检查要记录实际使用的 Java 可执行文件。
- 一对一双向视频持续 5 分钟；验证音频、双向画面、挂断、接听为语音及再次呼叫。记录双方 CPU、收发码率、帧率、丢包和抖动。
- 会议先测 3 人，再测 8 人与第 9 人拒绝；按“首次入 room-101 → 挂断 → 再次进入”复验黑框和无框问题。
- 按 [视频性能验收](video-performance.md) 执行摄像头启停、缩放、DPI 变化和反复挂断各 20 次，记录每类实际次数、失败和崩溃。
- 验证 HTTP 房间聊天、私聊离线回收、文件收发、双端 CDR 去重和人数恢复；截图只能证明界面，媒体须有实际双向流或统计。
- 前端验收先在原生最大化或全屏下检查，再真实调整宽、高、宽高比；记录尺寸。无界面固定视口测试不替代这些检查。
- 将实现状态同步到需求分析、用户分析、UML、README、依赖与构建说明、答辩口径和最终报告。涉及三份分工或整体规划接口表时由三角色共同核对。

阶段 6 完成条件是新包、对应版本证据、真实双机及多人验收、报告和交付命名均闭环。现有 zero-config-acceptance.log 来自 2026-10-26，验证范围主要为 macOS JVM/JNI 探针及 Windows 文件存在性，不能覆盖上述运行验收。

## 下一次开发从哪里开始

本轮离线范围已完成并保留分支、报告和可复验 Windows 包。服务器恢复后，优先由 MCU 同学检查服务端 CPU、混流帧率和画布，再结合新媒体统计区分网络、编码、解码与显示瓶颈。双机与入会验收须等用户重新安排，不能自动恢复执行。原 [审查探针](reviews/probes/Pr49CdrCompatibilityProbeTest.java) 仅保留作初始问题证据，正式回归已进入 admin 测试集。
