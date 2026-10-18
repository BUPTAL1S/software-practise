package lab.sip.client;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 跨平台原生渲染层镜像控制器（macOS Cocoa / CALayer 与 Windows Win32 / WS_EX_LAYOUTRTL 硬件级水平翻转）。
 * 针对本地摄像头预览应用水平翻转，满足用户自视的“照镜子”生理直觉；
 * 保持远端 RTP 视频传输流为光学真实视角，互不干扰。
 */
public final class NativeMirror {
  private static volatile boolean loaded = false;
  private static volatile boolean loadAttempted = false;

  private NativeMirror() {}

  /**
   * 检查当前系统与运行时是否支持原生渲染层镜像翻转。
   */
  public static boolean isSupported() {
    ensureLoaded();
    return loaded;
  }

  /**
   * 对指定的原生窗口或视图句柄应用水平镜像（X 轴翻转）并更新窗口标题。
   *
   * @param hwnd 原生窗口或视图句柄（macOS 为 NSWindow / NSView 指针，Windows 为 HWND）
   * @param title 翻转后要设置的窗口标题，为 null 或空字符串则不修改标题
   */
  public static void applyPreviewMirror(long hwnd, String title) {
    if (hwnd == 0) {
      return;
    }
    String os = System.getProperty("os.name", "").toLowerCase();
    if (!os.contains("mac") && !os.contains("win")) {
      return;
    }
    ensureLoaded();
    if (loaded) {
      try {
        applyMirror(hwnd, title);
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * 恢复指定的原生窗口或视图为正常光学视角（取消镜像翻转）。
   */
  public static void restoreNormal(long hwnd, String title) {
    if (hwnd == 0) {
      return;
    }
    String os = System.getProperty("os.name", "").toLowerCase();
    if (!os.contains("mac") && !os.contains("win")) {
      return;
    }
    ensureLoaded();
    if (loaded) {
      try {
        restoreNormalNative(hwnd, title);
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * 查询当前原生视图是否处于水平镜像状态。
   */
  public static boolean isMirrored(long hwnd) {
    if (hwnd == 0) {
      return false;
    }
    String os = System.getProperty("os.name", "").toLowerCase();
    if (!os.contains("mac") && !os.contains("win")) {
      return false;
    }
    ensureLoaded();
    if (loaded) {
      try {
        return isMirroredNative(hwnd);
      } catch (Throwable ignored) {
      }
    }
    return false;
  }

  private static synchronized void ensureLoaded() {
    if (loaded) {
      return;
    }
    if (loadAttempted) {
      return;
    }
    loadAttempted = true;
    String os = System.getProperty("os.name", "").toLowerCase();
    if (!os.contains("mac") && !os.contains("win")) {
      return;
    }
    try {
      Path root = NativeLoader.findRepoRoot();
      if (os.contains("mac")) {
        Path dylib = root.resolve("native/macos-arm64/libmacmirror.dylib");
        if (!Files.isRegularFile(dylib)) {
          dylib = root.resolve("native/macos-x64/libmacmirror.dylib");
        }
        if (!Files.isRegularFile(dylib)) {
          dylib = root.resolve("native/libmacmirror.dylib");
        }
        if (Files.isRegularFile(dylib)) {
          System.load(dylib.toAbsolutePath().toString());
          loaded = true;
        }
      } else if (os.contains("win")) {
        Path dll = root.resolve("native/windows-x64/winmirror.dll");
        if (!Files.isRegularFile(dll)) {
          dll = root.resolve("native/winmirror.dll");
        }
        if (Files.isRegularFile(dll)) {
          System.load(dll.toAbsolutePath().toString());
          loaded = true;
        }
      }
    } catch (UnsatisfiedLinkError e) {
      if (e.getMessage() != null && e.getMessage().contains("already loaded")) {
        loaded = true;
      } else {
        loaded = false;
      }
    } catch (Throwable t) {
      loaded = false;
    }
  }

  // JNI native exports (implemented in native/macos-arm64/NativeMirror.m and native/windows-x64/NativeMirrorWin.c)
  private static native void applyMirror(long hwnd, String title);
  private static native void restoreNormalNative(long hwnd, String title);
  private static native boolean isMirroredNative(long hwnd);
}
