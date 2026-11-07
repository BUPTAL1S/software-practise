package lab.sip.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Optional, process-local AAC decoder for the JavaFX MediaPlayer on Windows. */
final class WindowsPreviewAudio {
  private static final System.Logger LOG = System.getLogger(WindowsPreviewAudio.class.getName());
  private static boolean attempted;

  private WindowsPreviewAudio() {}

  // Called on the attachment loader, before constructing the first MediaPlayer.
  static synchronized void initialize() {
    if (attempted) return;
    attempted = true;
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
        || !("amd64".equalsIgnoreCase(System.getProperty("os.arch", ""))
            || "x86_64".equalsIgnoreCase(System.getProperty("os.arch", "")))
        || !Boolean.parseBoolean(System.getProperty("sip.preview.nativeAudio", "true"))) return;
    try {
      String override = System.getProperty("sip.preview.audio.dir");
      Path directory = override == null || override.isBlank()
          ? NativeLoader.libraryFile().getParent().resolve("preview-runtime") : Path.of(override);
      directory = directory.toAbsolutePath();
      Path helper = directory.resolve("previewaudio.dll");
      if (!Files.isRegularFile(helper) || !Files.isRegularFile(directory.resolve("LAVAudio.ax"))) {
        LOG.log(System.Logger.Level.DEBUG, "Optional native preview audio runtime is not installed");
        return;
      }
      System.load(helper.toString());
      int result = initialize0(directory.resolve("LAVAudio.ax").toString());
      if (result != 0) {
        LOG.log(System.Logger.Level.WARNING, "Native preview audio setup failed: 0x{0}",
            Integer.toHexString(result));
        return;
      }
      Runtime.getRuntime().addShutdownHook(new Thread(WindowsPreviewAudio::shutdown0, "preview-audio-shutdown"));
      LOG.log(System.Logger.Level.INFO, "JavaFX native preview: process-local LAV AAC decoder enabled");
    } catch (LinkageError | RuntimeException failure) {
      LOG.log(System.Logger.Level.WARNING, "Optional native preview audio could not be initialized", failure);
    }
  }

  private static native int initialize0(String decoder);
  private static native void shutdown0();
}
