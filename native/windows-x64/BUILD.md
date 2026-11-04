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
推荐使用 `scripts/build-native-windows.ps1 -PjSource <独立2.17源码目录> -JdkHome <Windows-x64-JDK17>`，会检查固定提交、按顺序应用或确认释放、延迟、视口三个补丁，保留已有配置并固定 x64 host。脚本不会自动替换安装中的 DLL。手工构建也必须先应用这三个补丁。

初始 DLL 的 SHA256 为 `e19935eb972d3bbe55dd29fe30820184ff66c0ddfd7c4b8f6a54857ae9c2610a`，保留备份。
最终延迟修复 DLL 为 25418163 字节，SHA256 `060e37ee39066a02de62185f81e1779ea9332abac0f9a1291f25abb084b9ea7d`；3065 个 JNI 导出与初始 DLL 一致。源码补丁见 [video-latency-2.17.patch](patches/video-latency-2.17.patch)，不提交 DLL 二进制。
原生最小解码延迟参数从 100ms 改为 40ms、优先最新画面，并修正帧尾缺包裁剪索引。环境变量 `SIP_VIDEO_LATENCY_MODE=legacy` 恢复旧缓存策略；组帧错误修复始终保留。24 组合成接收验证和边界见 [延迟报告](../../docs/reports/media-latency-20261030.md)。
新 Windows 便携包入口及不依赖开发 PATH 的验证方式见 [Windows 交付](../../docs/windows-delivery.md)。

### 编译本地镜像组件 winmirror.dll

Windows 端本地摄像头预览的“照镜子”翻转功能由 `winmirror.dll` 提供：
- **在 Windows 上编译**：直接双击运行 `native/windows-x64/build-winmirror.bat`；
- **在 macOS/Linux 上交叉编译**：运行 `./native/windows-x64/build-winmirror.sh`（依赖 `mingw-w64`）。

需要 Windows SDL2 开发头文件。`SDL_INCLUDE_DIR` 指向包含 `SDL2/SDL.h` 的目录；Windows 默认 `C:\msys64\mingw64\include`，MSYS2 默认 `/mingw64/include`。镜像按 SDL renderer 所属 HWND 选择，只翻转本地预览纹理，远端画面与 Win32 坐标不翻转。

### 2026-11-04 原生布局接口升级

`callwindow.dll` 必须由当前 `CallWindowWin.c` 重建，Java 侧只接受布局 ABI 2；旧 DLL 不会被误用。设置 `JAVA_HOME`、把 MinGW64 `bin` 加到 PATH 后运行 `build-callwindow.bat` 和 `build-winmirror.bat`。发布时同时更新 `pjsua2.dll`、`callwindow.dll`、`winmirror.dll`。

[video-viewport-2.17.patch](patches/video-viewport-2.17.patch) 恢复 SDL 2.32 在 Windows 无边框窗口创建过程中丢失的 RESIZABLE 标志，并在每帧绘制时按真实 renderer 输出尺寸居中等比缩放，避免父窗口已变大而视频仍停在旧视口左上角。本地 PIP 以同级子窗口放置，不重复嵌套；原生布局调用在 Windows 专用工作线程执行，并合并连续缩放请求。Windows 镜像辅助库也检查 ABI 2，防止误用会翻转远端画面的旧 DLL。两个旧版布局 JNI 入口保留原签名，新的带缩放参数入口单独命名。

按照仓库 PR 规范，移除此前误跟踪的 `callwindow.dll` 二进制；本地文件保留。检出源码后必须重建辅助库，便携 ZIP 则包含已验证的三个 DLL。

本机合成视频验证：`mvn -f client/pom.xml -Dtest=NativeLayoutIntegrationTest -Dsip.nativeLayoutTest=true test`。它使用两个 Colorbar 设备，验证最大化、连续 32 次缩放、远端摄像头关闭后仅保留预览和镜像隔离。输出截图在 `client/target/test-snapshots/`；这是本机原生窗口验证，不代表双机真实通话验收。macOS 源码同步了 ABI，但须在 Mac 重新编译和实测。

## 启动

仓库根目录下 `native/windows-x64` 已有 dll 后，在 `client/` 双击 `启动.bat`，或：

```bat
cd client
mvn javafx:run
```

`NativeLoader` 用绝对路径 `System.load` 加载 `pjsua2.dll`。若仍报缺依赖，把 MinGW 的 `bin` 加到 PATH，或把缺的 dll 和 `pjsua2.dll` 放在同一目录。
