# Windows 客户端与后台便携交付

Windows x64 的 BAT 便携包构建入口为 `scripts/build-windows.ps1`；原生 EXE 客户端与单文件安装包使用下文的 `scripts/build-windows-exe.ps1`。接收包的电脑无需安装 Java、Maven 或 MSYS2；构建电脑仍需 Windows x64 JDK 17、PowerShell 7、Maven 和现有原生 DLL。
客户端与后台分开组包，后台部署到 MCU 同学的主机；本脚本不启动或修改 Docker、Kamailio、FreeSWITCH。

## 构建

在仓库根目录运行，工具路径按本机修改：

```powershell
pwsh -File scripts/build-windows.ps1 `
  -JdkHome 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot' `
  -Maven 'C:\Users\murd3r17\tools\apache-maven-3.9.9\bin\mvn.cmd' `
  -Offline
```

`-Offline` 只使用本地 Maven 缓存；缺打包插件时会明确失败。首次构建若需要补齐 POM 声明的依赖，可不传该开关。
输出默认在 `dist/windows-<时间>/`，已有输出目录不会覆盖。构建始终执行 clean package，清除旧编译产物；测试应先独立通过，构建使用 `-DskipTests`，不是运行验收。

- `JavaSIP-Client-Windows-x64.zip`：JavaFX 客户端、包内 Windows JRE、JNI 和所有同级 DLL。
- `JavaSIP-Backend-Windows-x64.zip`：Spring Boot 后台及包内 Windows JRE。
- `manifest.json`：构建提交、dirty 状态、Java 版本、模块和 JAR/DLL 的 SHA256。
- 每个 ZIP 内另带 `build-id.txt` 和 `manifest.json`；客户端启动日志显示构建号，原生崩溃转储写入包内 `logs/`。

构建验证 JDK 的真实 OS/架构，缺必需 DLL 或 JavaFX Windows 图形类/库时失败。
JRE 包含 `java.se` 以及 JavaFX/加密/字符集/JFR 等补充模块，覆盖 Spring/Hibernate 对 naming、management、instrumentation、SQL、XML 的需求；不只分析客户端后省略后台所需模块。
`pjsua2.dll` 当前导入 `libopenh264-7.dll`，需保留原名。NativeLoader 已支持预加载这个版本化名称。
延迟修复后，组包还检查 DLL 的 x64 PE 类型和低延迟策略构建标记，拒绝静默复用旧 DLL。原生重建入口为 `scripts/build-native-windows.ps1`，不自动安装；补丁、回退变量和验证见 [延迟报告](reports/media-latency-20261030.md)。
构建只复制 JAR、DLL、运行时和启动说明，不打入本机 `.env`、客户端偏好、现有数据库或上传文件。

2026-11-04 起，发布必须同时包含视口补丁和布局/Windows 镜像 ABI 2 辅助库；构建会拒绝缺少新导出的旧 helper。源码仓库不跟踪 DLL，请先按 [原生构建说明](../native/windows-x64/BUILD.md) 重建。不能只换 JAR 后继续使用旧 DLL。

## 独立包验证

```powershell
pwsh -File scripts/verify-windows-package.ps1 -PackageDirectory 'D:\path\to\dist\windows-<时间>'
```

验证时清除开发 JDK/MSYS2 的 PATH，先用包内 JRE 调用 NativeLoader，然后启动包内后台：

- 先逐项核对 manifest 中 JAR/DLL 的 SHA256，再运行包内诊断脚本，拒绝漏换或混装。

- HTTP 只绑定 127.0.0.1，系统自动分配端口。
- ESL 禁用，数据库和头像目录隔离到 `validation-<随机值>/`。
- 校验 JSON 健康状态、演示账号登录、8 用户、2 房间、0 话单。
- 等待初始化完成，结束后按本轮进程句柄与启动时间停止自己启动的 Java。
- 保存 stdout/stderr 和 `result.json`，不记录登录 token。

该验证证明 JNI 依赖与后台运行时完整，不代表已验证 JavaFX 的最终桌面启动、摄像头、麦克风或网络通话。本轮没有可操作的第二台电脑，不执行双机及入会验收。

## 接收端使用

客户端解压后运行 `start-client.bat`，在既有账号设置中填正确的 SIP 和 HTTP 地址。默认媒体档为 balanced；必要时先设置 `SIP_MEDIA_PROFILE=legacy` 回退。详细参数见 [媒体说明](video-performance.md)。

排查旧包或 DLL 混装时运行 `collect-diagnostics.bat`，输出保存在 `logs/bundle-diagnostics.txt`，包含构建号、Java/OS/架构、每个 DLL 的文件名、大小与 SHA256，并实际加载 JNI 和两个辅助库。该脚本不读取账号偏好，不注册 SIP，不打开摄像头/麦克风。报告卡死时同时保留本次构建号和 `logs/hs_err_pid*.log`（如果 JVM 已产生转储）；单纯无响应未必有崩溃转储。

后台解压后运行 `start-backend.bat`，默认端口 8080，可传 `--server.port=8088` 等 Spring 参数。
数据库和头像上传在 `data/`；现有其他上传路径仍相对包目录。保持控制台打开，Ctrl+C 停止当前实例。
离线测试可在启动前设置 `ESL_ENABLED=false`；实际部署在 MCU 主机时需核对 `ESL_HOST`、`ESL_PORT`、`ESL_PASSWORD`。
ESL 可继续只发布到该宿主回环，不为异机后台额外暴露 8021。不要误停其他端口或其他项目的 Java。

## Windows EXE 客户端与安装包

`scripts/build-windows-exe.ps1` 只封装客户端，通过 JDK 17 自带的 `jpackage` 生成原生启动器，再生成单个 `.exe` 安装包。默认执行客户端 `clean package -Ppackage-windows -DskipTests`，保证从当前源码构建；应用测试应在打包前独立完成。它不重编译后台，也不启动或停止后台、Docker、Kamailio、FreeSWITCH。

完整打包除上述环境外还需 **WiX 3** 的 `candle.exe` 与 `light.exe`。可从 [WiX 官方 3.14.1 发布页](https://github.com/wixtoolset/wix3/releases/tag/wix3141rtm) 下载 `wix314-binaries.zip` 解压，使用 `-WixDirectory` 指向这两个 EXE 所在目录，或将目录加入构建进程的 PATH。构建脚本不安装系统软件。WiX 仅供构建安装包使用，接收端不需要它。

```powershell
pwsh -File scripts/build-windows-exe.ps1 `
  -JdkHome 'C:\path\to\jdk-17' `
  -Maven 'C:\path\to\maven\bin\mvn.cmd' `
  -WixDirectory 'C:\tools\wix314' `
  -Offline
```

只生成免安装 EXE 时使用 `-AppImageOnly`，无需 WiX。`-NativeDirectory` 可指定已编译的 Windows 原生 DLL 目录；默认读取 `native/windows-x64`。它与旧构建入口一样检查 x64、延迟/视口/SDL 事件补丁标记及布局/镜像 ABI 2。可选 AAC 运行时仅在客户端代码支持且本机已安装 `preview-runtime/previewaudio.dll` 时随包复制，并保留整个目录及其许可证。

```powershell
pwsh -File scripts/build-windows-exe.ps1 `
  -JdkHome 'C:\path\to\jdk-17' -AppImageOnly `
  -OutputDirectory 'D:\交付目录\SIP Connect'
```

默认输出 `dist/windows-exe-<时间>/`，已有目录会拒绝覆盖：

- `portable/SIPConnect/SIPConnect.exe`：可直接双击的免安装客户端，须保留整个 `SIPConnect` 文件夹一起使用。
- `SIPConnect-portable.zip`：便于复制的完整客户端与包内 Java。
- `installer/SIPConnect-0.1.0.exe`：单文件安装包（`-AppImageOnly` 时省略），使用当前用户安装，提供安装位置及快捷方式选择。
- `manifest.json`：源码提交、dirty 状态、构建号、Java/JavaFX 版本、运行时模块，以及应用文件和分发产物的大小、SHA256。
- `build/`：中间输入与裁剪后的 Java 运行时；`evidence/`：包验证结果。

`-AppName` 与 `-AppVersion` 可调整分发名称和 Windows 版本号；版本号默认取 Maven 项目版本，去掉 `-SNAPSHOT` 等后缀，须为三个数字组成的版本。`-UpgradeUuid` 标识安装升级系列，同一发行渠道应保持不变；如另行制作预览版渠道，应同时指定独立名称和 UUID，避免替换已安装的正式版。

EXE 使用包内 Java，原生库位置相对于应用目录解析，支持含空格的安装路径及从其他工作目录启动。JVM 崩溃报告写入应用目录的 `logs/`。包中不包含开发机数据库、上传文件、环境文件或账号偏好。用户在现有设置界面填写 HTTP/SIP 地址；音视频通话与会议仍依赖现有 Spring Boot、Kamailio、FreeSWITCH 服务。

### EXE 包验证

```powershell
pwsh -File scripts/verify-windows-exe.ps1 `
  -PackageDirectory 'D:\交付目录\SIP Connect' -Gui
```

验证先核对全部应用文件和安装包/ZIP 的 SHA256，并检查便携 ZIP 中存在 EXE/包内 Java、没有开发机数据库及偏好。随后清空 `JAVA_HOME` 和开发工具 PATH，用包内 Java 实际加载 PJSUA2 与两个辅助库，并从独立工作目录运行 EXE 的 `--version`。

`-Gui` 会额外打开 EXE，确认出现可响应的桌面窗口，再仅关闭本次验证启动的进程及其 jpackage 子进程；省略它适合无交互构建验证。结果与日志保存在 `evidence/verify-<随机值>/`。验证不自动安装 EXE 安装包，不登录账号、注册 SIP、打开摄像头/麦克风或发起通话，不能替代接收端的通话验收。

二进制、运行时、ZIP、安装包和构建日志仍位于忽略的 `dist/` 中，源码 PR 只保存构建脚本和说明。

## 旧跨平台脚本

`scripts/build-dist.sh` 仅适用于 macOS arm64，防止 Windows jlink 产物被标成 macOS。
缺 `scripts/cache/win-jre.zip` 时提前失败；Windows 包应优先使用本机专用构建入口。
本轮没有验证 macOS JRE、macOS JNI 或旧一体化主机包，不能把新的 Windows 后台包测试用于证明它们可运行。
