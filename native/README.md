# native

各平台自己编译 PJSIP **2.17** 的 PJSUA2 JNI。二进制 gitignore，不要提交。

| 系统 | 怎么编 | 放到哪 |
|---|---|---|
| macOS arm64 | [macos-arm64/BUILD.md](macos-arm64/BUILD.md) | `libpjsua2.jnilib` 或 `libpjsua2.dylib` |
| Windows x64 | [windows-x64/BUILD.md](windows-x64/BUILD.md) | `pjsua2.dll` 及 MinGW 依赖 dll |

SWIG 生成的 Java 在 `client/src/main/java/org/pjsip/pjsua2/`，两平台共用同一 2.17。
