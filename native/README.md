# native

各平台自己编译 PJSIP **2.17** 的 PJSUA2 JNI。二进制 gitignore，不要提交。

| 系统 | 怎么编 | 放到哪 |
|---|---|---|
| macOS arm64 | [macos-arm64/BUILD.md](macos-arm64/BUILD.md) | `libpjsua2.jnilib` 或 `libpjsua2.dylib` |
| Windows x64 | [windows-x64/BUILD.md](windows-x64/BUILD.md) | `pjsua2.dll` 及 MinGW 依赖 dll |

SWIG 生成的 Java 在 `client/src/main/java/org/pjsip/pjsua2/`，两平台共用同一 2.17。

## 视频编解码约定

跨端（Win / Mac / MCU）统一采用 **H.264**（RFC 6184，时钟 90000）：
- Windows 使用 MSYS2 MinGW OpenH264（`mingw-w64-x86_64-openh264`）；
- macOS 使用硬件加速 VideoToolbox 或 OpenH264；
- Kamailio 透传 SDP，FreeSWITCH 会议室接收 H.264。
