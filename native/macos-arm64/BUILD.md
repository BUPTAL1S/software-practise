# 在 macOS arm64 上编译 libpjsua2.dylib

PJSIP **2.17**。产物不入库。阶段 2 开启视频支持（macOS 原生 VideoToolbox 硬件 H.264 与 AVFoundation/Metal 驱动）。

```bash
brew install swig openjdk@17 sdl2
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
git clone --branch 2.17 --depth 1 https://github.com/pjsip/pjproject.git ~/src/pjproject
cd ~/src/pjproject
git apply /path/to/software-practise/native/macos-arm64/pjsip-metal-fix.patch
printf '%s\n' 'export CFLAGS += -fPIC' > user.mak
printf '%s\n' '#define PJMEDIA_HAS_VIDEO 1' '#define PJMEDIA_HAS_VID_TOOLBOX_CODEC 1' > pjlib/include/pj/config_site.h
./configure --disable-ffmpeg --disable-libwebrtc
make dep
make -j"$(sysctl -n hw.ncpu)"
cd pjsip-apps/src/swig/java
make
```

把 `output/libpjsua2.jnilib` 拷到本目录（也可再复制一份名为 `libpjsua2.dylib`）。`NativeLoader` 两种文件名都认。若 `otool -L` 显示还依赖 Homebrew 的 gnutls/mbedtls/SDL2，启动前保持这些 brew 包在默认路径，或把对应 dylib 拷到本目录并设 `DYLD_LIBRARY_PATH`。

SWIG 生成的 `org.pjsip.pjsua2` Java 覆盖复制到 `client/src/main/java/org/pjsip/pjsua2/`。
