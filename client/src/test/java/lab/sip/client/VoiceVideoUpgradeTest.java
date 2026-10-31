package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual local SIP re-INVITE and H264 sender, using Colorbar instead of a physical camera. */
class VoiceVideoUpgradeTest {
  @ParameterizedTest @ValueSource(ints={200, 488})
  void voiceCallCanUpgradeOrContinueAfterRejection(int response) throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException ignored) { }
    Platform.setImplicitExit(false);
    String previous = System.getProperty("sip.captureDeviceName");
    System.setProperty("sip.captureDeviceName", "Colorbar");
    var logs = new java.util.concurrent.CopyOnWriteArrayList<String>();
    SipService sip = new SipService(new SipService.Events() {
      public void log(String message) { logs.add(message); }
      public void log(String category, String message) { logs.add(category + " " + message); }
      public void sip(boolean out, String summary, String raw) {}
      public void stateChanged() {}
    });
    try (var peer = new CallConnectedNotificationTest.Peer()) {
      try {
        sip.start(5096, "127.0.0.1");
        assertTrue(sip.queryVideoDetails().captureDev.toLowerCase().contains("colorbar"));
        sip.register("127.0.0.1:" + peer.socket.getLocalPort(), "1098", "", "lab", "udp");
        await(sip::isRegistered);
        sip.call("1099", "lab", false);
        assertTrue(peer.invited.await(3, TimeUnit.SECONDS)); peer.answer(false);
        await(sip::isCallConnected);
        assertFalse(sip.isCameraEnabled(), "Voice call must start with camera off");
        peer.upgradeStatus = response;
        sip.setCallVideoTransmit(true);
        await(() -> peer.inviteCount.get() >= 2);
        if (response == 200) {
          await(sip::isCurrentCallVideo);
          await(() -> peer.videoStates.stream().anyMatch(VideoStateMessage::enabled));
          long enabledSequence = peer.videoStates.stream().filter(VideoStateMessage::enabled).mapToLong(VideoStateMessage::sequence).max().orElseThrow();
          sip.setCallVideoTransmit(false);
          await(() -> peer.videoStates.stream().anyMatch(state -> !state.enabled() && state.sequence() > enabledSequence));
          assertFalse(sip.isCameraEnabled());
        } else {
          await(() -> !sip.isCameraEnabled());
          assertFalse(sip.isCurrentCallVideo());
        }
        assertTrue(sip.isCallConnected(), "Camera changes must preserve the audio call");
      } catch (Throwable failure) {
        throw new AssertionError(String.join("\n", logs), failure);
      } finally { sip.hangup(); peer.bye.await(2, TimeUnit.SECONDS); sip.shutdown(); }
    } finally {
      if (previous == null) System.clearProperty("sip.captureDeviceName"); else System.setProperty("sip.captureDeviceName", previous);
    }
  }
  private static void await(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
    assertTrue(condition.getAsBoolean(), "Condition did not become true within 5 seconds");
  }
}
