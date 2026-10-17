package lab.sip.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NativeMirrorTest {

  @Test
  void testNativeMirrorSafety() {
    // 句柄为 0 或无效指针时不应抛出异常或崩溃
    NativeMirror.applyPreviewMirror(0, "摄像头预览 (镜像)");
    NativeMirror.applyPreviewMirror(0, null);
    NativeMirror.restoreNormal(0, "摄像头预览");
    NativeMirror.restoreNormal(0, null);
    assertFalse(NativeMirror.isMirrored(0));

    // 检查支持性（macOS arm64 环境下已编译 libmacmirror.dylib，应当返回 true）
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac")) {
      assertTrue(NativeMirror.isSupported(), "在 macOS 上 NativeMirror 动态库应当加载成功");
    } else if (os.contains("win")) {
      assertTrue(NativeMirror.isSupported(), "在 Windows 上 NativeMirror 动态库应当加载成功");
    }
  }

  @Test
  void testWindowsAndMacArtifacts() {
    Path root = NativeLoader.findRepoRoot();
    Path winDll = root.resolve("native/windows-x64/winmirror.dll");
    Path winBat = root.resolve("native/windows-x64/build-winmirror.bat");
    Path winSh = root.resolve("native/windows-x64/build-winmirror.sh");
    Path winSrc = root.resolve("native/windows-x64/NativeMirrorWin.c");

    assertTrue(Files.isRegularFile(winDll), "Windows 镜像动态库 winmirror.dll 应当存在");
    assertTrue(Files.isRegularFile(winBat), "Windows 编译脚本 build-winmirror.bat 应当存在");
    assertTrue(Files.isRegularFile(winSh), "Windows 跨平台编译脚本 build-winmirror.sh 应当存在");
    assertTrue(Files.isRegularFile(winSrc), "Windows 源码 NativeMirrorWin.c 应当存在");

    try {
      assertTrue(Files.size(winDll) > 1024, "winmirror.dll 文件大小应大于 1KB");
    } catch (Exception e) {
      org.junit.jupiter.api.Assertions.fail(e);
    }
  }

  @Test
  void testNativeLoaderDoesNotPreloadMirrorLibraries() throws Exception {
    Path root = NativeLoader.findRepoRoot();
    Path nativeLoaderSource = root.resolve("client/src/main/java/lab/sip/client/NativeLoader.java");
    String code = Files.readString(nativeLoaderSource);
    assertFalse(code.contains("\"winmirror.dll\""), "NativeLoader 不应包含 winmirror.dll 预加载");
    assertFalse(code.contains("\"libmacmirror.dylib\""), "NativeLoader 不应包含 libmacmirror.dylib 预加载");
  }

  @Test
  void testSipServiceMirrorStateManagement() {
    SipService.Events dummyEvents = new SipService.Events() {
      @Override
      public void log(String msg) {}

      @Override
      public void log(String category, String msg) {}

      @Override
      public void sip(boolean outgoing, String summary, String raw) {}

      @Override
      public void stateChanged() {}
    };
    SipService sip = new SipService(dummyEvents);

    // 默认应当开启镜像预览（符合照镜子直觉）
    assertTrue(sip.isPreviewMirrorEnabled(), "摄像头预览默认应当开启镜像模式");

    // 切换为真实模式
    sip.togglePreviewMirror();
    assertFalse(sip.isPreviewMirrorEnabled(), "切换后应当为非镜像真实模式");

    // 再次切换回镜像模式
    sip.togglePreviewMirror();
    assertTrue(sip.isPreviewMirrorEnabled(), "再次切换后应当恢复镜像模式");
  }
}
