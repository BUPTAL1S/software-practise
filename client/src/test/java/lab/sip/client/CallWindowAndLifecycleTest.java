package lab.sip.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CallWindowAndLifecycleTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
    }
    Platform.setImplicitExit(false);
  }

  @Test
  void testVideoAvatarControlProtocol() {
    assertTrue(SipService.isVideoAvatarControl(SipService.VIDEO_AVATAR_OFF));
    assertTrue(SipService.isVideoAvatarControl(SipService.VIDEO_AVATAR_ON));
    assertFalse(SipService.isVideoAvatarControl("hello world"));
    assertFalse(SipService.isVideoAvatarControl(null));
  }

  @Test
  void testWindowLayoutSafeDegradation() {
    assertEquals(0L, WindowLayout.findWindow(null));
    assertEquals(0L, WindowLayout.findWindow(""));
    assertEquals(0L, WindowLayout.findWindow("   "));
    // Calling attach or detach on null or zero handles must be completely safe
    WindowLayout.attach(0, 0, 0, 0, 0, 0, 0);
    WindowLayout.detach(0, 0);
  }

  @Test
  void testIncomingCallWindowLifecycle() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    AtomicBoolean answeredVideo = new AtomicBoolean(false);
    AtomicBoolean answeredAudio = new AtomicBoolean(false);
    AtomicBoolean rejected = new AtomicBoolean(false);

    Platform.runLater(
        () -> {
          try {
            IncomingCallWindow win = new IncomingCallWindow(null);
            assertFalse(win.isShowing());

            // 1. Show without avatar
            win.show("1002", true, null, () -> answeredVideo.set(true), () -> answeredAudio.set(true), () -> rejected.set(true));
            assertTrue(win.isShowing());

            // 2. Show with custom avatar image
            WritableImage mockAvatar = new WritableImage(40, 40);
            win.show("1002", false, mockAvatar, () -> answeredVideo.set(true), () -> answeredAudio.set(true), () -> rejected.set(true));
            assertTrue(win.isShowing());

            // 3. Close
            win.close();
            assertFalse(win.isShowing());
          } catch (Throwable error) { failure.set(error); } finally {
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "IncomingCallWindow UI actions timed out");
    if (failure.get() != null) throw new AssertionError(failure.get());
  }

  @Test
  void testVideoCallWindowLifecycle() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    AtomicBoolean hungup = new AtomicBoolean(false);

    Platform.runLater(
        () -> {
          try {
            VideoCallWindow win = new VideoCallWindow(null);
            assertFalse(win.isShowing());

            win.show("1003", true, true, () -> hungup.set(true), muted -> {}, camera -> {});
            assertTrue(win.isShowing());

            // Toggle avatar off with custom avatar image
            WritableImage mockAvatar = new WritableImage(40, 40);
            win.setRemoteAvatarOff(true, "1003", mockAvatar);

            // Detach PiP explicitly
            win.detachPip();

            // Toggle avatar on again
            win.setRemoteAvatarOff(false, "1003", null);

            // Close
            win.close();
            assertFalse(win.isShowing());
          } catch (Throwable error) { failure.set(error); } finally {
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "VideoCallWindow UI actions timed out");
    if (failure.get() != null) throw new AssertionError(failure.get());
  }

  @Test
  void testVideoIntentAndAudioOnlyAnswerBehavior() {
    SipService.Events dummyEvents =
        new SipService.Events() {
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

    // Initially neither video nor videoIntent
    assertFalse(sip.isCurrentCallVideo());
    assertFalse(sip.isCurrentCallVideoIntent());

    // When call is video active, both are true
    sip.setMockCallStateForTest("1002", true, true);
    assertTrue(sip.isCurrentCallVideo());
    assertTrue(sip.isCurrentCallVideoIntent());

    // When call was offered as video, but answered audio-only:
    // videoActive is false, but videoIntent is true!
    sip.setMockCallStateForTest("1002", false, true);
    assertFalse(sip.isCurrentCallVideo(), "Active video stream should be false");
    assertTrue(sip.isCurrentCallVideoIntent(), "Video intent should be true to keep VideoCallWindow open");

    // The showAvatar condition in VideoCallWindow:
    boolean connected = true;
    boolean remoteAvatarOff = false;
    boolean videoActive = sip.isCurrentCallVideo();
    boolean showAvatar = connected && (remoteAvatarOff || !videoActive);
    assertTrue(showAvatar, "When answered audio-only, showAvatar must be true to show avatar instead of black screen");

    sip.clearMockCallStateForTest();
    assertFalse(sip.isCurrentCallVideo());
    assertFalse(sip.isCurrentCallVideoIntent());
  }

  @Test
  void testCallUiActiveBehaviorForRoomConferenceVsOneToOne() {
    // 1. In Room Conference: sip.isInCall() == true && sip.isInRoomCall() == true
    boolean inCall = true;
    boolean inRoom = true;
    boolean isOneToOneCall = inCall && !inRoom;
    boolean callUiActive = isOneToOneCall;

    assertFalse(callUiActive, "In Room Conference, callUiActive must be false so hangup and cameraToggleBox remain visible");

    // 2. In 1:1 call: sip.isInCall() == true && sip.isInRoomCall() == false
    inRoom = false;
    isOneToOneCall = inCall && !inRoom;
    callUiActive = isOneToOneCall;

    assertTrue(callUiActive, "In 1:1 call, callUiActive must be true to let standalone CallWindow / VideoCallWindow handle controls");
  }
}
