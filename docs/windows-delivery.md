# Windows 客户端与后台便携交付

本轮 Windows x64 构建入口为 `scripts/build-windows.ps1`。接收包的电脑无需安装 Java、Maven 或 MSYS2；构建电脑仍需 Windows x64 JDK 17、PowerShell 7、Maven 和现有原生 DLL。
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

构建验证 JDK 的真实 OS/架构，缺必需 DLL 或 JavaFX Windows 图形类/库时失败。
JRE 包含 `java.se` 以及 JavaFX/加密/字符集/JFR 等补充模块，覆盖 Spring/Hibernate 对 naming、management、instrumentation、SQL、XML 的需求；不只分析客户端后省略后台所需模块。
`pjsua2.dll` 当前导入 `libopenh264-7.dll`，需保留原名。NativeLoader 已支持预加载这个版本化名称。
构建只复制 JAR、DLL、运行时和启动说明，不打入本机 `.env`、客户端偏好、现有数据库或上传文件。

## 独立包验证

```powershell
pwsh -File scripts/verify-windows-package.ps1 -PackageDirectory 'D:\path\to\dist\windows-<时间>'
```

验证时清除开发 JDK/MSYS2 的 PATH，先用包内 JRE 调用 NativeLoader，然后启动包内后台：

- HTTP 只绑定 127.0.0.1，系统自动分配端口。
- ESL 禁用，数据库和头像目录隔离到 `validation-<随机值>/`。
- 校验 JSON 健康状态、演示账号登录、8 用户、2 房间、0 话单。
- 等待初始化完成，结束后按本轮进程句柄与启动时间停止自己启动的 Java。
- 保存 stdout/stderr 和 `result.json`，不记录登录 token。

该验证证明 JNI 依赖与后台运行时完整，不代表已验证 JavaFX 的最终桌面启动、摄像头、麦克风或网络通话。本轮没有可操作的第二台电脑，不执行双机及入会验收。

## 接收端使用

客户端解压后运行 `start-client.bat`，在既有账号设置中填正确的 SIP 和 HTTP 地址。默认媒体档为 balanced；必要时先设置 `SIP_MEDIA_PROFILE=legacy` 回退。详细参数见 [媒体说明](video-performance.md)。

后台解压后运行 `start-backend.bat`，默认端口 8080，可传 `--server.port=8088` 等 Spring 参数。
数据库和头像上传在 `data/`；现有其他上传路径仍相对包目录。保持控制台打开，Ctrl+C 停止当前实例。
离线测试可在启动前设置 `ESL_ENABLED=false`；实际部署在 MCU 主机时需核对 `ESL_HOST`、`ESL_PORT`、`ESL_PASSWORD`。
ESL 可继续只发布到该宿主回环，不为异机后台额外暴露 8021。不要误停其他端口或其他项目的 Java。

## 旧跨平台脚本

`scripts/build-dist.sh` 仅适用于 macOS arm64，防止 Windows jlink 产物被标成 macOS。
缺 `scripts/cache/win-jre.zip` 时提前失败；Windows 包应优先使用本机专用构建入口。
本轮没有验证 macOS JRE、macOS JNI 或旧一体化主机包，不能把新的 Windows 后台包测试用于证明它们可运行。
