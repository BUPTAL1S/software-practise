package lab.sip.client;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cross-platform native window helper for the local PiP window.
 *
 * <p>PJSIP renders both the remote video and the camera preview into native SDL
 * windows. JavaFX cannot place those windows directly, so a tiny JNI helper
 * positions the preview window over the remote window. If the helper is missing
 * the feature degrades gracefully and the preview stays a normal window.
 */
public final class WindowLayout {
  private static volatile boolean attempted;
  private static volatile boolean loaded;

  private WindowLayout() {}

  public static boolean isAvailable() {
    ensureLoaded();
    return loaded;
  }

  /** Position {@code pip} as a small always-on-top window in the remote window's lower-right corner. */
  public static void placePip(long remoteWindow, long pipWindow) {
    if (remoteWindow == 0 || pipWindow == 0) {
      return;
    }
    ensureLoaded();
    if (!loaded) {
      return;
    }
    try {
      placePipNative(remoteWindow, pipWindow);
    } catch (Throwable ignored) {
    }
  }

  /** Restore the preview window to a normal top-level window. */
  public static void restore(long pipWindow) {
    if (pipWindow == 0) {
      return;
    }
    ensureLoaded();
    if (!loaded) {
      return;
    }
    try {
      restoreNative(pipWindow);
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
        Path dir = root.resolve(
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

  private static native void placePipNative(long remoteWindow, long pipWindow);

  private static native void restoreNative(long pipWindow);
}
