package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MediaTuningTest {
  @Test void balancedImprovesTargetWithinTheExistingLevel() {
    var tuning = MediaTuning.defaults();
    assertEquals(848, tuning.width()); assertEquals(480, tuning.height()); assertEquals(25, tuning.fps());
    assertEquals(1_200_000, tuning.averageBps());
    assertTrue(((tuning.width()+15)/16)*((tuning.height()+15)/16)*tuning.fps() <= 40500);
  }
  @Test void legacyAndMotionAreExplicitReversibleProfiles() {
    Properties values = new Properties(); values.setProperty("sip.media.profile", "legacy");
    var legacy = MediaTuning.read(values);
    assertEquals(15, legacy.fps()); assertEquals(500_000, legacy.averageBps()); assertFalse(legacy.tuneAudio());
    values.setProperty("sip.media.profile", "motion");
    assertEquals(30, MediaTuning.read(values).fps());
  }
  @Test void incompatibleResolutionAndFrameRateAreRejected() {
    for (String[] setting : new String[][]{{"sip.video.width","1280"},{"sip.video.height","721"},
        {"sip.video.fps","30"},{"sip.video.width","849"},{"sip.media.profile","unknown"}}) {
      Properties values = new Properties(); values.setProperty(setting[0],setting[1]);
      assertThrows(IllegalArgumentException.class, () -> MediaTuning.read(values));
    }
  }
  @Test void invalidJitterOrderingAndUnboundedDiagnosticsAreRejected() {
    for (String[] setting : new String[][]{{"sip.audio.jitterMinMs","100"},{"sip.audio.jitterMaxMs","60"},
        {"sip.media.statisticsSeconds","1"},{"sip.video.maximumBps","300000"},{"sip.video.mtu","9000"}}) {
      Properties values = new Properties(); values.setProperty(setting[0],setting[1]);
      assertThrows(IllegalArgumentException.class, () -> MediaTuning.read(values));
    }
  }
  @Test void audioAndVideoHaveIndependentBoundedLatencyBudgets() {
    var defaults = MediaTuning.defaults();
    assertEquals(40, defaults.captureMs()); assertEquals(40, defaults.playbackMs());
    assertEquals(160, defaults.jitterMaxMs()); assertEquals(0, defaults.videoJitterInitialMs());
    assertEquals(200, defaults.videoJitterMaxMs());
    Properties values = new Properties(); values.setProperty("sip.video.jitterMaxMs","60");
    assertThrows(IllegalArgumentException.class, () -> MediaTuning.read(values));
    values.clear(); values.setProperty("sip.video.jitterMinMs","20");
    assertThrows(IllegalArgumentException.class, () -> MediaTuning.read(values));
  }
}
