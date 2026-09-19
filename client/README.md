# client

JavaFX 桌面端。代码按 [docs/分工-客户端.md](../docs/分工-客户端.md) 后续添加，此处不预置空包。

## 原生库

启动时按系统加载，不要写死一种 OS：

| 系统 | 目录 | 库名 |
|---|---|---|
| Windows x64 | `native/windows-x64/` | `pjsua2.dll` |
| macOS arm64 | `native/macos-arm64/` | `libpjsua2.dylib` |

PJSIP **2.17**，SWIG Java 绑定。Win / Mac 必须同一上游版本，否则 Java 类对不上。

## SIP 约定

- 注册到组内 Kamailio（compose 默认 `5060`）
- 一对一：`INVITE sip:<user>@lab`
- 房间：`INVITE sip:room-<id>@lab`（id 来自后台）
- 文字：SIP MESSAGE
- 文件：后台 HTTP，不走 SIP 包体
