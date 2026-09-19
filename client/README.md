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
# macOS
./启动.command
# 或
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
mvn javafx:run
```

```bat
REM Windows
启动.bat
```

同机开两个客户端时，把「本机 SIP 端口」改成 `5060` 和 `5062`。SIP 服务器填组内 Kamailio 的 IP，域 `lab`，呼叫对方用户名即可（会变成 `sip:<user>@lab`）。

## SIP 约定

- 一对一：`INVITE sip:<user>@lab`，不进 MCU
- 阶段 1 不做视频、MESSAGE、文件、房间、后台登录
