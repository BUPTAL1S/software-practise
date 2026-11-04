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
    return attach(parent, remote, pip, x, y, w, h, 0, 0);
  }

  public static boolean attach(long parent, long remote, long pip, int x, int y, int w, int h,
      double scaleX, double scaleY) {
    if (parent == 0 || (remote == 0 && pip == 0) || w <= 0 || h <= 0) {
      return false;
    }
    ensureLoaded();
    if (!loaded) {
      return false;
    }
    try {
      return attachScaledNative(parent, remote, pip, x, y, w, h, scaleX, scaleY);
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
      if (!os.contains("win") && !os.contains("mac")) return;
      Path lib = NativeLoader.helperFile("callwindow.dll", "libcallwindow.dylib");
      if (!Files.isRegularFile(lib)) {
        return;
      }
      System.load(lib.toAbsolutePath().toString());
      loaded = layoutApiVersionNative() == 2;
    } catch (Throwable ignored) {
      loaded = false;
    }
  }

  private static native long findWindowNative(String title);

  private static native int layoutApiVersionNative();
  /** Windows diagnostic snapshot: parent, x, y, width, height, visible; empty if unavailable. */
  static long[] describe(long handle) {
    ensureLoaded();
    if (!loaded) return new long[0];
    try { return describeNative(handle); } catch (Throwable unavailable) { return new long[0]; }
  }
  private static native long[] describeNative(long handle);
  private static native boolean attachScaledNative(
      long parent, long remote, long pip, int x, int y, int w, int h, double scaleX, double scaleY);

  private static native void detachNative(long remote, long pip);
}
