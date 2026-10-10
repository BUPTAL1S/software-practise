# client

JavaFX 桌面端，Win / Mac **同一份代码**。阶段 2：REGISTER + 一对一音视频通话与主界面。

## 准备

- JDK **17**（`JAVA_HOME` 指向它）
- Maven
- 本机编好的 PJSUA2 2.17 原生库（开启视频支持，见下方）

## 编 JNI（阶段 2 视频支持）

| 系统 | 文档 | 产物（不入库） | 编解码器与视频后端 |
|---|---|---|---|
| macOS arm64 | [native/macos-arm64/BUILD.md](../native/macos-arm64/BUILD.md) | `native/macos-arm64/libpjsua2.dylib` | 硬件加速 VideoToolbox H.264 + Metal / AVFoundation |
| Windows x64 | [native/windows-x64/BUILD.md](../native/windows-x64/BUILD.md) | `native/windows-x64/pjsua2.dll` | Cisco OpenH264 + DirectShow / SDL2 |

SWIG 生成的 Java 放在 `src/main/java/org/pjsip/pjsua2/`，两平台共用，必须都是 2.17。

### 跨端 H.264 编解码器统一约定

全组音视频统一采用 **H.264** 编解码（SDP 编码名 `H264`，时钟率 90000，Packetization-Mode 1）：
1. **macOS**：原生启用硬件加速 `VideoToolbox` H.264，亦支持 Cisco `openh264`；
2. **Windows**：依赖 MSYS2 MinGW-w64 提供的 Cisco `openh264`；
3. **Kamailio**：透传 SDP 视频 m-line，不转码，两端直接协商建立 RTP 视频流；
4. **FreeSWITCH MCU**：后续阶段 5 视频会议室统一接收 H.264。

> **阶段 1 纯音频库平滑兼容**：如果使用阶段 1 编译的无视频库启动，客户端主界面会自动检测并显示「阶段 1 纯音频模式」，呼叫与接听自动降级为语音通话，所有 SIP 信令与注册保持完全可用。

## 启动

在 `client/` 目录：

```bash
# macOS 窗口 1（1001）
./启动.command
# 同机窗口 2（1002）
./启动.command --user=1002 --port=5062 --peer=1001
```

```bat
REM Windows 窗口 1
启动.bat
REM 窗口 2
启动.bat --user=1002 --port=5062 --peer=1001
```

或直接用 Maven：

```bash
mvn javafx:run
mvn javafx:run -Djavafx.args="--user=1002 --port=5062 --peer=1001"
```

本机 SIP 端口若被占用会自动 +2。不要两个窗口用同一个账号（usrloc 会被后注册的顶掉）。

## 界面与音视频操作

1. **启动引擎**：自动检测本机 SIP 端口（默认 5060）、探测 H.264 视频编解码器及摄像头/渲染设备。
2. **注册**：输入 SIP 服务器 IP（Kamailio 宿主机校园网 IP，或同机 Docker 填 127.0.0.1），点「注册」。
3. **摄像头预览**：可点击「摄像头预览」检查本地画面。在发起呼叫或点击接听时，客户端会自动关闭预览以释放摄像头硬件，避免设备冲突。
4. **视频呼叫 / 语音呼叫**：
   - 点「视频呼叫」发送含音视频媒体（H.264 + PCMU）的 INVITE。
   - 点「语音呼叫」仅协商音频媒体。
5. **接听**：收到来电提示时，可选择「接听(视频)」或「接听(语音)」。
6. **视频画面呈现**：音视频接通后，底层 PJSUA2 自动弹出独立的低延迟硬件加速原生视频窗口展示远端画面。
7. **挂断**：任意一端点击「挂断」，将发送 BYE 释放呼叫并关闭视频窗口，对端显示「对端已挂断」。

## 填什么

- **SIP 服务器**：Kamailio **所在那台机器**的校园网 IP（信令跑 `start-kamailio` 后会打印）。同机访问 Docker 可填 `127.0.0.1`。
- 启动时会记住上次成功注册的代理（`client/.local-prefs`，不入库），也可点「填入本机 IP」。
- 域：`lab`（不要填成 IP）
- 用户：`1001` / `1002`（与信令静态账号一致）
- 密码：任意非空（Kamailio 目前无鉴权）
- 传输：必须 **TCP**（UDP 对组里这套 Windows Docker Kamailio 会 408）。不要用界面把 UDP 存进偏好。

## SIP 约定

- 一对一：`INVITE sip:<user>@lab`（支持音视频双向），不进 MCU。
- 阶段 2 目标：音视频呼叫、H.264 协商、摄像头预览、独立视频窗口、阶段 1 纯音频平滑降级。
- 后续规划：阶段 3（MESSAGE + 文件传输）、阶段 4（后台登录与 CDR）、阶段 5（MCU 视频会议室）、阶段 6（打包分发）。
