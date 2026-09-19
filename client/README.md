# client

JavaFX 桌面端，Win / Mac **同一份代码**。阶段 1：REGISTER + 一对一音频。

## 准备

- JDK **17**（`JAVA_HOME` 指向它）
- Maven
- 本机编好的 PJSUA2 2.17 原生库（见下方）

## 编 JNI

| 系统 | 文档 | 产物（不入库） |
|---|---|---|
| macOS arm64 | [native/macos-arm64/BUILD.md](../native/macos-arm64/BUILD.md) | `native/macos-arm64/libpjsua2.dylib` |
| Windows x64 | [native/windows-x64/BUILD.md](../native/windows-x64/BUILD.md) | `native/windows-x64/pjsua2.dll` |

SWIG 生成的 Java 放在 `src/main/java/org/pjsip/pjsua2/`，两平台共用，必须都是 2.17。

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

或：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
mvn javafx:run
mvn javafx:run -Djavafx.args="--user=1002 --port=5062 --peer=1001"
```

本机 SIP 端口若被占用会自动 +2。不要两个窗口用同一个账号（usrloc 会被后注册的顶掉）。

## 填什么

- **SIP 服务器**：Kamailio **所在那台机器**的校园网 IP（信令跑 `start-kamailio` 后会打印）。不是「本机 IP」的同义词。
- 启动时会记住上次成功注册的代理（`client/.local-prefs`，不入库），也可点「填入本机 IP」。
- 域：`lab`（不要填成 IP）
- 用户：`1001` / `1002`（与信令静态账号一致）
- 密码：任意非空（阶段 1 Kamailio 无鉴权）
- 传输：必须 **TCP**（UDP 对组里这套 Windows Docker Kamailio 会 408）。不要用界面把 UDP 存进偏好。
- 来电：只让一边点「呼叫」，对端日志出现「来电」后再点「接听」。不要两边同时打。
- 挂断：对端应出现「对端已挂断」或 `DISCONNECTED`。若只有本侧「已挂断」、对端仍停在 CONFIRMED，是 BYE 没转到（信令需重启带 TCP Record-Route 的 Kamailio）。
- 若日志出现 `172.20.0.1` 的 IP change，是旧包还在把 Contact 改写成 Docker 网桥，需要换成当前客户端。

本机探测到的校园网 IPv4 只用于 PJSIP Contact advertise，避免注册成 `127.0.0.1` 导致对端 INVITE 回不来。监听仍绑 `0.0.0.0`。

## SIP 约定

- 一对一：`INVITE sip:<user>@lab`，不进 MCU
- 阶段 1 不做视频、MESSAGE、文件、房间、后台登录
