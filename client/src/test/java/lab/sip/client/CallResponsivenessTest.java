package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import org.junit.jupiter.api.*;

class CallResponsivenessTest {
  @BeforeAll static void toolkit() {
    try { Platform.startup(() -> {}); } catch (IllegalStateException ignored) { }
    Platform.setImplicitExit(false);
  }
  @Test void uiSnapshotsDoNotWaitForTheNativeServiceMonitor() throws Exception {
    SipService sip = new SipService(new SipService.Events() {
      public void log(String text) {} public void sip(boolean out, String summary, String raw) {} public void stateChanged() {}
      public void log(String category, String text) {}
    });
    sip.setMockCallStateForTest("1002", true);
    CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
    Thread holder = new Thread(() -> { synchronized (sip) {
      locked.countDown(); try { release.await(); } catch (InterruptedException ignored) { }
    } });
    holder.start(); assertTrue(locked.await(1, TimeUnit.SECONDS));
    FutureTask<Void> read = new FutureTask<>(() -> {
      sip.isRegistered(); sip.isStarted(); sip.queryVideoDetails(); sip.isCallConnected(); sip.isInCall();
      sip.hasIncoming(); sip.isIncomingVideo(); sip.currentCallTarget(); sip.isInRoomCall();
      sip.isCurrentCallVideo(); sip.isCurrentCallVideoIntent(); sip.getActiveCallRemoteUri();
      sip.getActiveVideoWindowHandle(); sip.getPreviewWindowHandle(); sip.isPreviewActive(); sip.statusText();
      return null;
    });
    try { Platform.runLater(read); read.get(1, TimeUnit.SECONDS); }
    finally { release.countDown(); holder.join(1000); }
  }
  @Test void nativeTimeoutKeepsCameraBusyUntilTheOperationActuallyReturns() throws Exception {
    try (CallPreviewLifecycle lifecycle = new CallPreviewLifecycle(100)) {
      CountDownLatch release = new CountDownLatch(1), timeout = new CountDownLatch(1), completed = new CountDownLatch(1);
      lifecycle.execute(release::await, completed::countDown, error -> timeout.countDown());
      try {
        assertTrue(timeout.await(1, TimeUnit.SECONDS));
        assertTrue(lifecycle.isBusy(), "Timeout cannot release a still-running JNI task");
        FutureTask<Boolean> pulse = new FutureTask<>(() -> true);
        Platform.runLater(pulse); assertTrue(pulse.get(1, TimeUnit.SECONDS));
      } finally { release.countDown(); }
      assertTrue(completed.await(1, TimeUnit.SECONDS));
      assertFalse(lifecycle.isBusy());
    }
  }
  @Test void cameraUpdatesRejectOtherCallsPeersAndReorderedMessages() {
    var newest = new VideoStateMessage("dialog-2", 10, false);
    assertEquals(newest, VideoStateMessage.parse(newest.encode()));
    assertFalse(newest.appliesTo("dialog-1", "sip:1002@lab", "sip:1002@lab", 0));
    assertFalse(newest.appliesTo("dialog-2", "sip:1002@lab", "sip:1003@lab", 0));
    assertFalse(newest.appliesTo("dialog-2", "sip:1002@lab", "sip:1002@lab", 11));
    assertTrue(newest.appliesTo("dialog-2", "sip:1002@lab", "sip:1002@lab", 9));
    assertNull(VideoStateMessage.parse(VideoStateMessage.PREFIX + "broken"));
  }
}
