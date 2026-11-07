# Windows 原生附件视频预览

适用范围是聊天附件 MP4 / M4V 的预览，不是摄像头预览或 SIP 视频通话。

本机 Windows 11 25H2 的复现结果：原始 H.264 + AAC MP4 在 JavaFX 17.0.15 / 17.0.21 下报 `ERROR_MEDIA_INVALID`；拆出 AAC 后，系统 DirectShow AAC 过滤器虽然可以创建，却不能正常加入解码图。仅升级 JavaFX 21 仍会被 AAC 音轨阻止播放。JavaFX 21 加进程内 LAV AAC 解码器后，可使用原生 `MediaView` 播放。

Windows 视频解码器还会偶发在准备阶段没有输出首帧就失败。客户端仅对准备阶段的媒体错误重建播放器，最多五次，每次间隔 150 ms；六秒准备超时和已经开始播放后的错误继续走原有兼容播放。旧播放器的延迟回调不会影响新播放器。

暂停后拖动进度时，会确认解码器是否到达目标位置，必要时有限重发定位请求；保持暂停状态，不通过短暂播放来完成定位。

## 本机安装

要求 Windows x64、PowerShell 7、Windows x64 JDK 17、MinGW-w64 x64 的 `g++`。在仓库根目录运行（按本机实际路径填写）：

```powershell
./scripts/setup-native-preview-windows.ps1 `
  -JdkHome 'C:/path/to/jdk-17' `
  -Compiler 'C:/msys64/mingw64/bin/g++.exe'
```

脚本从 [LAV 官方 0.83 发布页](https://github.com/Nevcairiel/LAVFilters/releases/tag/0.83) 下载便携包，校验官方 SHA256，再编译独立的 `previewaudio.dll`。产物位于 `native/windows-x64/preview-runtime/`，不入 Git。已下载官方压缩包时可传 `-Archive 'C:/path/to/LAVFilters-0.83-x64.zip'` 离线安装。

重启客户端后，第一次打开视频时会在后台加载。成功日志为：

```text
JavaFX native preview: process-local LAV AAC decoder enabled
```

播放器仍由 JavaFX 负责解复用、视频显示、播放时钟、音频输出及播放控件；只替换本进程失效的 AAC 解码路径。没有重新编码附件、移除声音或逐帧转换 Java 图片。JDK 保持 17，JavaFX 的各模块统一使用 21.0.13。

脚本只解压便携包，不执行包内安装脚本，不调用 `regsvr32`，不写 Windows 解码器注册表、不替换系统 DLL。Kamailio、FreeSWITCH、后台、SIP/SDP/RTP 和 PJSUA2 都不参与这次修复。

## 加载与打包

默认从 `sip.native.dir` 对应的原生库目录下寻找 `preview-runtime`；源码启动时自动定位 `native/windows-x64/preview-runtime`。也可通过 JVM 参数 `-Dsip.preview.audio.dir=C:/path/to/preview-runtime` 指定完整目录。

LAV 必须随运行时保留官方 `COPYING`、README 和依赖文件。Windows 打包脚本在安装完成后会复制整个目录；没有安装运行时时保持系统解码器及原有兼容播放。源码启动不依赖系统范围的编解码器安装。

OpenJFX 的 DirectShow 后端固定列出若干解码器，而且对第一个解码器要求输入类型枚举，LAV 不提供这种枚举。因此桥接在 `CLSCTX_INPROC_SERVER` 范围内使用现有备用槽位，并让第一条失效的 AAC 路径跳过。注册对象在本进程退出时撤销，其他应用不会看到这些类工厂。LAV 设置仅保存在内存中。

## 验证与禁用

在 `client` 目录运行：

```powershell
mvn '-Dtest=AttachmentPreviewWindowTest#fetchedMp4PlaysPausesSeeksAndCleansUpAfterClose' test
mvn test
```

原生测试要求真实 `MediaView` 画面包含测试图案、保留 AAC 音轨、按正常速度推进、支持暂停/拖动，并在关闭后释放播放器和下载缓存；不会把兼容播放器当作原生测试通过。

临时禁用进程内 AAC 桥接可使用 JVM 参数 `-Dsip.preview.nativeAudio=false` 并重启。恢复时移除该参数。不同格式、损坏文件、缺少视频解码器等情形仍可能使用兼容播放；此修复不宣称支持任意视频编码。

已知限制：独立的启动 JAR 诊断窗口仍捕获过偶发首播进度提前到片尾、状态保持 `PLAYING` 的情况，尚未完全定位。正式客户端的用户手动验证正常，但不能据此保证所有文件及启动环境都稳定。复测时应同时观察画面、声音和实际播放时长，而不只确认出现原生窗口。

参考：[OpenJFX Windows 解码器实现](https://github.com/openjdk/jfx21u/blob/master/modules/javafx.media/src/main/native/gstreamer/plugins/dshowwrapper/dshowwrapper.cpp)、[Microsoft 进程内 COM 注册说明](https://learn.microsoft.com/en-us/windows/win32/api/combaseapi/ne-combaseapi-regcls)。
