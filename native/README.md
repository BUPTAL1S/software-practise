# native

各平台自己编译 PJSIP 2.17 的 PJSUA2 JNI，放到对应目录。二进制被 `.gitignore` 忽略。

```
native/windows-x64/    pjsua2.dll 及运行依赖
native/macos-arm64/    libpjsua2.dylib
```

Intel Mac 若需要，再加 `macos-x64/`，不要覆盖 arm64。

构建步骤跟官方文档：https://docs.pjsip.org/en/latest/pjsua2/building.html （Java SWIG）。编完在组内网盘或 Release 附件互传即可，不要把几百 MB 的上游源码推进本仓库。
