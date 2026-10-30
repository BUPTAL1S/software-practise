package lab.sip.client;

import java.util.Locale;
import java.util.Properties;

/** Bounded LAN profiles within the project's existing H.264 Level 3.0 contract. */
record MediaTuning(String profile, int width, int height, int fps, int averageBps, int maximumBps,
                   int mtu, boolean tuneAudio, int captureMs, int playbackMs, int jitterInitialMs,
                   int jitterMinMs, int jitterMaxPreMs, int jitterMaxMs, int statisticsSeconds) {
  static MediaTuning read(Properties values) {
    String profile = values.getProperty("sip.media.profile", "balanced").trim().toLowerCase(Locale.ROOT);
    int width, height, fps, average, maximum;
    switch (profile) {
      case "balanced" -> { width = 848; height = 480; fps = 25; average = 1_200_000; maximum = 1_800_000; }
      case "motion" -> { width = 640; height = 360; fps = 30; average = 900_000; maximum = 1_400_000; }
      case "legacy" -> { width = 640; height = 360; fps = 15; average = 500_000; maximum = 800_000; }
      default -> throw new IllegalArgumentException("sip.media.profile must be balanced, motion or legacy");
    }
    width = number(values, "sip.video.width", width, 160, 1200);
    height = number(values, "sip.video.height", height, 120, 800);
    fps = number(values, "sip.video.fps", fps, 10, 30);
    average = number(values, "sip.video.averageBps", average, 200_000, 4_000_000);
    maximum = number(values, "sip.video.maximumBps", maximum, average, 6_000_000);
    int macroblocks = ((width + 15) / 16) * ((height + 15) / 16);
    if ((width & 1) != 0 || (height & 1) != 0 || macroblocks > 1620 || macroblocks * fps > 40500) {
      throw new IllegalArgumentException("video dimensions/fps exceed H.264 Level 3.0 or I420 bounds");
    }
    boolean audio = !profile.equals("legacy");
    int capture = number(values, "sip.audio.captureMs", 60, 20, 200);
    int playback = number(values, "sip.audio.playbackMs", 60, 20, 200);
    int min = number(values, "sip.audio.jitterMinMs", 20, 0, 500);
    int initial = number(values, "sip.audio.jitterInitialMs", 60, min, 500);
    int maxPre = number(values, "sip.audio.jitterMaxPreMs", 120, initial, 1000);
    int max = number(values, "sip.audio.jitterMaxMs", 240, maxPre, 1000);
    int interval = number(values, "sip.media.statisticsSeconds", 5, 0, 60);
    if (interval == 1) throw new IllegalArgumentException("statistics interval must be 0 or at least 2 seconds");
    return new MediaTuning(profile, width, height, fps, average, maximum,
        number(values, "sip.video.mtu", 1200, 600, 1400), audio, capture, playback,
        initial, min, maxPre, max, interval);
  }

  static MediaTuning defaults() { return read(new Properties()); }

  private static int number(Properties values, String key, int fallback, int min, int max) {
    String text = values.getProperty(key);
    int value;
    try { value = text == null ? fallback : Integer.parseInt(text.trim()); }
    catch (NumberFormatException error) { throw new IllegalArgumentException(key + " must be an integer", error); }
    if (value < min || value > max) throw new IllegalArgumentException(key + " must be in [" + min + "," + max + "]");
    return value;
  }
}
