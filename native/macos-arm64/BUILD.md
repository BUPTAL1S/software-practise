# 在 macOS arm64 上编译 libpjsua2.dylib

PJSIP **2.17**。产物不入库。阶段 2 开启视频支持（跨平台统一 Cisco OpenH264 编解码器与 AVFoundation/Metal 驱动）。

## 编解码器约定（双端对齐）

全组统一采用 **H.264** 编解码（SDP 编码名 `H264`，时钟率 90000，Packetization-Mode 1，载荷类型 97）：
- **Windows**：编译依赖 Cisco `openh264`，由 MSYS2 MinGW-w64 提供；
- **macOS**：通过 Homebrew `openh264` 编译 OpenH264，确保与 Windows 双端 payload type 97 与 profile-level-id 严格一致；
- **Kamailio**：透传 SDP 视频 m-line，不转码；
- **FreeSWITCH MCU**：阶段 5 会议室统一接受 H.264。

## 准备与编译

```bash
brew install swig openjdk@17 sdl2 openh264
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
git clone --branch 2.17 --depth 1 https://github.com/pjsip/pjproject.git ~/src/pjproject
cd ~/src/pjproject

# 应用 Metal 渲染补丁（修复 MTKView 重复展示 drawable 冲突及帧丢弃卡顿）
git apply /path/to/repo/native/macos-arm64/pjsip-metal-fix.patch

printf '%s\n' 'export CFLAGS += -fPIC' > user.mak
printf '%s\n' '#define PJMEDIA_HAS_VIDEO 1' '#define PJMEDIA_HAS_OPENH264_CODEC 1' '#define PJMEDIA_HAS_VID_TOOLBOX_CODEC 0' > pjlib/include/pj/config_site.h

./configure --with-openh264="$(brew --prefix openh264)" --disable-vtoolbox --disable-ffmpeg --disable-libwebrtc
make dep
make -j"$(sysctl -n hw.ncpu)"
cd pjsip-apps/src/swig/java
make
```

把 `output/libpjsua2.jnilib` 拷到仓库根目录 `native/macos-arm64/libpjsua2.dylib`（及 `libpjsua2.jnilib`）。
同时将依赖的 `libopenh264.dylib` 与 `libSDL2-2.0.0.dylib`（或建立软链接 `libSDL2.dylib`）拷入 `native/macos-arm64/` 同级目录。`NativeLoader` 会在加载 `libpjsua2.dylib` 前自动预加载这些同级依赖。

SWIG 生成的 `org.pjsip.pjsua2` Java 覆盖复制到 `client/src/main/java/org/pjsip/pjsua2/`。
