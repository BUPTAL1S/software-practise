package lab.sip.client;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cross-platform native window helper for embedding PJSIP's native remote video
 * and camera preview windows into the single JavaFX call window.
 */
public final class WindowLayout {
  private static volatile boolean attempted;
  private static volatile boolean loaded;

  private WindowLayout() {}

  public static boolean isAvailable() {
    ensureLoaded();
    return loaded;
  }

  /** Find a top-level window by its exact title (JavaFX stage title). */
  public static long findWindow(String title) {
    if (title == null || title.isBlank()) {
      return 0;
    }
    ensureLoaded();
    if (!loaded) {
      return 0;
    }
    try {
      return findWindowNative(title);
    } catch (Throwable ignored) {
      return 0;
    }
  }

  /**
   * Reparent the PJSIP remote video and preview windows into the JavaFX window,
   * positioning them inside the video area.
   *
   * @param parent  JavaFX top-level native window handle
   * @param remote  PJSIP VideoWindow native handle
   * @param pip     PJSIP VideoPreview native handle, 0 for none
   * @param x       video area x in the JavaFX client area
   * @param y       video area y in the JavaFX client area
   * @param w       video area width
   * @param h       video area height
   */
  public static boolean attach(
      long parent, long remote, long pip, int x, int y, int w, int h) {
    if (parent == 0 || remote == 0 || w <= 0 || h <= 0) {
      return false;
    }
    ensureLoaded();
    if (!loaded) {
      return false;
    }
    try {
      attachNative(parent, remote, pip, x, y, w, h);
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** Detach PJSIP windows from the JavaFX parent before closing the call window. */
  public static void detach(long remote, long pip) {
    ensureLoaded();
    if (!loaded) {
      return;
    }
    try {
      detachNative(remote, pip);
    } catch (Throwable ignored) {
    }
  }

  private static synchronized void ensureLoaded() {
    if (attempted) {
      return;
    }
    attempted = true;
    String os = System.getProperty("os.name", "").toLowerCase();
    try {
      Path root = NativeLoader.findRepoRoot();
      Path lib;
      if (os.contains("win")) {
        lib = root.resolve("native/windows-x64/callwindow.dll");
      } else if (os.contains("mac")) {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        Path dir =
            root.resolve(
                (arch.contains("aarch64") || arch.contains("arm64"))
                    ? "native/macos-arm64"
                    : "native/macos-x64");
        lib = dir.resolve("libcallwindow.dylib");
      } else {
        return;
      }
      if (!Files.isRegularFile(lib)) {
        return;
      }
      System.load(lib.toAbsolutePath().toString());
      loaded = true;
    } catch (Throwable ignored) {
      loaded = false;
    }
  }

  private static native long findWindowNative(String title);

  private static native void attachNative(
      long parent, long remote, long pip, int x, int y, int w, int h);

  private static native void detachNative(long remote, long pip);
}
