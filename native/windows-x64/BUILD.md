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
export LDFLAGS += -lopenh264
EOF
printf '%s\n' '#define PJMEDIA_HAS_VIDEO 1' '#define PJMEDIA_HAS_OPENH264_CODEC 1' > pjlib/include/pj/config_site.h
./configure --host=x86_64-w64-mingw32 --enable-video --with-sdl=/mingw64 --disable-ffmpeg --disable-libwebrtc
make dep
make -j8 lib
cd pjsip-apps/src/swig/java
make output/pjsua2.dll
```

把生成的 `pjsua2.dll` 拷到仓库根目录 `native/windows-x64/`：

```text
native/windows-x64/pjsua2.dll
```

并放入相关依赖 DLL 到 `native/windows-x64/` 目录：
- `winmirror.dll`（原生摄像头预览镜像控制器，双击 `build-winmirror.bat` 或在 Mac/Linux 运行 `build-winmirror.sh` 编译生成）
- `libwinpthread-1.dll`
- `libgcc_s_seh-1.dll`
- `libssp-0.dll`
- `libstdc++-6.dll`
- `libopenh264-7.dll`（本机当前构建的 PE 导入名；其他构建须保留它实际导入的 DLL 原名，不能只重命名成 openh264.dll）
- `SDL2.dll`
拷到 `native/windows-x64/` 同一目录。`NativeLoader` 会在加载 `pjsua2.dll` 前自动预加载这些同级依赖。

SWIG 生成的 Java 若与仓库 `client/src/main/java/org/pjsip/pjsua2/` 不一致，用 2.17 重新生成后覆盖那一份（Win/Mac 共用，不要各改各的）。

### 已有视频释放补丁与本轮证据

本机此前使用的释放补丁现归档在 [patches/video-hangup-2.17.patch](patches/video-hangup-2.17.patch)。
SHA256 为 `49e0fef86d1da9c9840160381253014e5fb84b23df27e78d0326e68e6401a7f6`；覆盖 DirectShow、SDL、视频端口/设备和 pjsua 视频窗口释放。
最初准备时对本机 pjproject 基线 `5a457451fa2712ba18e12b01738e8ff3af2b26fd` 的现有修改执行反向 dry-run 成功。随后为延迟优化在仓外独立检出重建了全部原生库与 JNI，没有改写原源码工作区。
推荐使用 `scripts/build-native-windows.ps1 -PjSource <独立2.17源码目录> -JdkHome <Windows-x64-JDK17>`，会检查固定提交、应用或确认两个补丁，保留已有配置并固定 x64 host。脚本不会自动替换安装中的 DLL。手工构建也必须先应用两个补丁。

初始 DLL 的 SHA256 为 `e19935eb972d3bbe55dd29fe30820184ff66c0ddfd7c4b8f6a54857ae9c2610a`，保留备份。
最终延迟修复 DLL 为 25418163 字节，SHA256 `060e37ee39066a02de62185f81e1779ea9332abac0f9a1291f25abb084b9ea7d`；3065 个 JNI 导出与初始 DLL 一致。源码补丁见 [video-latency-2.17.patch](patches/video-latency-2.17.patch)，不提交 DLL 二进制。
原生最小解码延迟参数从 100ms 改为 40ms、优先最新画面，并修正帧尾缺包裁剪索引。环境变量 `SIP_VIDEO_LATENCY_MODE=legacy` 恢复旧缓存策略；组帧错误修复始终保留。24 组合成接收验证和边界见 [延迟报告](../../docs/reports/media-latency-20261030.md)。
新 Windows 便携包入口及不依赖开发 PATH 的验证方式见 [Windows 交付](../../docs/windows-delivery.md)。

### 编译本地镜像组件 winmirror.dll

Windows 端本地摄像头预览的“照镜子”翻转功能由 `winmirror.dll` 提供：
- **在 Windows 上编译**：直接双击运行 `native/windows-x64/build-winmirror.bat`；
- **在 macOS/Linux 上交叉编译**：运行 `./native/windows-x64/build-winmirror.sh`（依赖 `mingw-w64`）。

## 启动

仓库根目录下 `native/windows-x64` 已有 dll 后，在 `client/` 双击 `启动.bat`，或：

```bat
cd client
mvn javafx:run
```

`NativeLoader` 用绝对路径 `System.load` 加载 `pjsua2.dll`。若仍报缺依赖，把 MinGW 的 `bin` 加到 PATH，或把缺的 dll 和 `pjsua2.dll` 放在同一目录。
