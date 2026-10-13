# 在 Windows x64 上编译 pjsua2.dll

本机是 Windows 时按此做。不要把 `.dll` 提交进 git。PJSIP **必须 2.17**，与 Mac 同一 tag。阶段 2 开启视频支持（OpenH264 编解码器与 DirectShow/SDL 视频驱动）。

官方说明：https://docs.pjsip.org/en/latest/pjsua2/building.html

## 编解码器约定（双端对齐）

全组统一采用 **H.264** 编解码（SDP 编码名 `H264`，时钟率 90000，Packetization-Mode 1，载荷类型 97）：
- **Windows**：编译依赖 Cisco `openh264`，由 MSYS2 MinGW-w64 提供；
- **macOS**：统一通过 Homebrew `openh264` 编译 Cisco OpenH264（载荷类型 97），关闭 VideoToolbox，确保与 Windows 双端 payload type 97 严格一致；
- **Kamailio**：透传 SDP 视频 m-line，不转码；
- **FreeSWITCH MCU**：阶段 5 会议室默认接受 H.264。

## 准备

- JDK 17（`JAVA_HOME` 指向它）
- [SWIG](https://www.swig.org/download.html) 加到 PATH
- MSYS2 + MinGW-w64（官方推荐 Windows 按 Unix 方式编）

## 步骤

在 MSYS2 MinGW64 终端（`C:\msys64\mingw64.exe`）：

```bash
pacman -S --needed git make mingw-w64-x86_64-gcc swig mingw-w64-x86_64-openh264 mingw-w64-x86_64-SDL2
git clone --branch 2.17 --depth 1 https://github.com/pjsip/pjproject.git "$HOME/src/pjproject"
cd "$HOME/src/pjproject"
cat > user.mak << 'EOF'
export CFLAGS += -fPIC
EOF
printf '%s\n' '#define PJMEDIA_HAS_VIDEO 1' '#define PJMEDIA_HAS_OPENH264_CODEC 1' > pjlib/include/pj/config_site.h
./configure --disable-ffmpeg --disable-libwebrtc
make dep
make -j"$(nproc)"
cd pjsip-apps/src/swig/java
make
```

把生成的 `pjsua2.dll` 拷到仓库根目录 `native/windows-x64/`：

```text
native/windows-x64/pjsua2.dll
```

并将 MinGW 依赖 DLL（从 `C:\msys64\mingw64\bin\` 拷贝）：
- `libwinpthread-1.dll`
- `libgcc_s_seh-1.dll`
- `libssp-0.dll`
- `libstdc++-6.dll`
- `libopenh264-*.dll`（或重命名为 `openh264.dll`）
- `SDL2.dll`
拷到 `native/windows-x64/` 同一目录。`NativeLoader` 会在加载 `pjsua2.dll` 前自动预加载这些同级依赖。

SWIG 生成的 Java 若与仓库 `client/src/main/java/org/pjsip/pjsua2/` 不一致，用 2.17 重新生成后覆盖那一份（Win/Mac 共用，不要各改各的）。

## 启动

仓库根目录下 `native/windows-x64` 已有 dll 后，在 `client/` 双击 `启动.bat`，或：

```bat
cd client
mvn javafx:run
```

`NativeLoader` 用绝对路径 `System.load` 加载 `pjsua2.dll`。若仍报缺依赖，把 MinGW 的 `bin` 加到 PATH，或把缺的 dll 和 `pjsua2.dll` 放在同一目录。
