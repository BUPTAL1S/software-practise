# 组员新 PR 对迁移交付的影响复核

复核日期：2026 年 11 月 4 日。需求依据为“将 Kamailio、FreeSWITCH 和 HTTP 后台全部迁移，并保持现有功能”。本记录复核部署和交付契约，不代表对组员全部代码正确性或目标服务器运行结果的认证。

## 1 本次快照和版本选择

本次重新读取远端 main 及五个组员 PR 的实际 diff。main 为 `9d99af9b2b2992b6f29d39ee988c0a45a742ea99`；#58 至 #62 在此次核对时均为 OPEN，未合并。PR #63 原稿仅按 main 所含 f25dad8 版本编写，确实没有覆盖以下候选集成功能。本修订已将条件要求同步到主手册。

不要仅依据“某个旧 PR 显示已合并”认定功能存在。应检查最终所选提交的实际源码、配置与构建输出。交付前记录完整 SHA、候选 PR 状态和 manifest；以后 PR 状态变化时重新核对本清单。

可选择当前 main 基线，也可在完成评审与集成验证后选择包含新功能的统一提交。后一种情况下，#60 包含 #59 的通话改动，合并及冲突处理应先 #59 后 #60；#58 和 #61 是独立功能分支；#62 的新交付包要求包含相关修复，不能把 #62 的打包脚本当作全部应用功能已经合入。这里不自动合并任何组员 PR。

## 2 后台与文件交接

[PR #58](https://github.com/BUPTAL1S/software-practise/pull/58) 的分支为 `admin/restore-hardening-20261104`，核对头提交为 `756466f24bbfc4c1daba17ae3c2cbf8c3ea3a2f7`。

- 私有附件新增下载权限：匿名返回 401，无关普通账号返回 403；已发布头像有单独公开判断。新版客户端增加带认证的预览下载，所以客户端与后台必须成对升级。
- 文件采用流式传输，单文件 64 MiB、multipart 65 MB，客户端上传等待 120 秒。代理限制须保留表单开销和实际上传耗时，不能沿用默认小请求限制。
- 身份和头像路径按 IdentityRules 校验。username 和 sip_id 允许以字母数字开头、其后字母数字下划线或连字符、长度至多 64，并排除 room- 前缀和特定 Windows 保留名。迁移前只读核查旧数据，不自动重命名真实成员。
- 消息连续分页、独立会议心跳、登录会话和注销清理也发生变化。需验证分页不漏消息、注销后旧任务停止、消息请求阻塞不拖累心跳。
- 对比未发现业务实体表定义变化。仍迁移原 users、rooms、files、cdr、private_messages 和 room_messages，不因此重建库、重注册用户或重编号。附件 files.path 的 Windows 至 Linux 转换仍需要执行。

验收须同时覆盖收发双方访问、无关账号拒绝、匿名拒绝、带认证图片预览、旧附件下载、较大文件和超限拒绝。不要将“未登录仍能下载私有文件”当作功能正常。

## 3 通话状态与语音升视频

[PR #59](https://github.com/BUPTAL1S/software-practise/pull/59) 的分支为 `client/restore-call-state-20261104`，核对头提交为 `765f123a9e3c3890f55efd34812ca66b08a127ac`。

该 PR 调整通话原生生命周期与摄像头启动和挂断竞态，引入绑定 Call-ID 和序列号的视频状态消息，并支持语音会话升视频。选用该版本时，应补验：

- 接受升级后原语音、通话身份和计时保持；拒绝或超时后语音仍可继续，不误显示摄像头已开启。
- 一端开启摄像头不自动开启另一端摄像头；快速切换、重同步和旧会话状态消息不会污染新通话。
- 视频刚接通就挂断、摄像头启动期间挂断、连续呼入呼出，都不产生原生退出或旧任务重新拉起窗口。

这些是候选版本的验收条件，不是当前 main 已支持语音升视频的声明。

## 4 原生库与视频布局

[PR #60](https://github.com/BUPTAL1S/software-practise/pull/60) 的分支为 `client/native-video-layout-20261104`，核对头提交为 `acf917b95929f5304ba750a0c5cfa037a6a4600d`，其历史包含 #59 的提交。

它升级窗口及镜像辅助库到 ABI 2，修改 PJSUA2 视口补丁，并移除 Git 中原先跟踪的 callwindow.dll。因此“拉源码后沿用旧 DLL”或“只更新一个 JAR”都不满足该版本的交付条件。

从所选集成源码按 native/windows-x64/BUILD.md 及 scripts/build-native-windows.ps1 重建，并按该版本要求依次应用释放、延迟、视口三补丁；同时构建 callwindow.dll 和 winmirror.dll。三个 DLL 必须与相应 Java 代码、架构和 SDL 依赖一致，按 NativeLoader 实际选择的原生目录放置。

验收新增最大化、持续缩放、实际窗口尺寸和 DPI、对端关闭视频后本地 PiP 继续显示且不越界、镜像只翻转本地预览。不能只看 JavaFX 外框变大；跨显示器 DPI 和成员机器摄像头仍需实机验证。

## 5 会议配置与布局文件

[PR #61](https://github.com/BUPTAL1S/software-practise/pull/61) 的分支为 `server/adaptive-conference-20261104`，核对头提交为 `247472381a715f0c0c9501d0cc3188222d98ff33`。

该 PR 将布局改成 `group:sip-adaptive`，新增 autoload_configs/conference_layouts.conf.xml，定义单格、双格、四格和九格。保留 video-mcu-3x3 profile、room-* 路由、960×540、15fps、1mb 和 8 人上限。

采用后须从同一提交完整复制两个会议 XML，不只复制 conference.conf.xml。交付模板会复制整个 autoload_configs，无须为此修改 SIP 端口或另开公网端口。先确认容器实际 /etc/freeswitch 内文件，再使用新建会议验证 1 人、2 人、3 至 4 人、5 至 8 人的视频布局及第 9 人限制，包含人数增减、纯语音参与和关闭摄像头。

当前 main 仍按固定 3×3 验收；采用 #61 后按自适应布局验收。旧 profile 名仍含 3x3，不表示新配置应继续固定九格。已有会议不能作为 reloadxml 后新 profile 已生效的证据。

## 6 构建证据与候选整合包

[PR #62](https://github.com/BUPTAL1S/software-practise/pull/62) 的分支为 `client/delivery-evidence-20261104`，核对头提交为 `7239bf0f0711546ebdc00a16f28855de74ded0c2`。

新增构建号、manifest、DLL 导出与哈希检查，以及 collect-diagnostics.bat。采用后应从干净的统一源码生成客户端与后台包，核对 build-id.txt、manifest.json、JAR/DLL SHA256，并运行 scripts/verify-windows-package.ps1。诊断脚本不读取账号偏好或注册 SIP，适合先排除混装。

组员报告的包构建提交是 `5d91dc8f8f3734149e791f40f9613ee298d3f917`，另有 `client/resume-integration-20261104` 候选整合分支。以上是组员报告的来源，不等于正式 main，也不等于本次复核重新构建并验证了这些二进制。正式交付前应确认实际拿到的包与批准的集成版本、manifest 相符；重新生成包时使用新哈希，不能抄旧报告中的 ZIP 哈希。

隔离包检查中的“8 个演示用户、2 个房间、ESL 禁用”只证明隔离测试环境，不可拿来要求正式数据库恢复演示用户或关闭正式 ESL。组员报告亦明确未完成实际协作者服务器、多机 MCU 或端到端延迟验收；迁移后仍须完成主手册的真实环境验收。

## 7 两项审查结论与本次修订

Standards 轴：对当前 main，端口、目录、环境变量和 ESL 模板未发现硬契约冲突。发现空房间库会触发后台初始化、破坏“启动后行数一致”的边界，已在 prepare_database.py 增加拒绝条件并补充说明；新增验证确认不会写入目标或改动源库。

Spec 轴：对“全量迁移并保留现有功能”，原稿缺少原生完整交付、自适应布局、文件权限及新版通话显示行为四组候选 PR 条件，现已同步到主手册的数据交接、客户端切换和验收项。当前仍未验证组员集成版本及目标服务器，不能据此宣布功能无故障。
