package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;

class VideoCallPresentationTest {
  @Test void connectedCallNeverShowsInvitationAfterCameraOrAvatarChanges() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.runLater(() -> {
      VideoCallWindow window = new VideoCallWindow(null);
      try {
        window.show("1001", true, false, () -> {}, value -> {}, value -> {});
        var field = VideoCallWindow.class.getDeclaredField("placeholder");
        field.setAccessible(true);
        Label placeholder = (Label) field.get(window);
        assertTrue(placeholder.getText().contains("接听"));
        window.update("1001", true, true);
        assertTrue(placeholder.getText().contains("已接通"));
        window.setRemoteAvatarOff(true, "1001");
        window.setRemoteAvatarOff(false, "1001");
        assertFalse(placeholder.getText().contains("邀请"));
        window.update("1001", false, true);
        assertTrue(placeholder.getText().contains("已接通"));
      } catch (Throwable error) {
        failure.set(error);
      } finally {
        window.close();
        done.countDown();
      }
    });
    assertTrue(done.await(8, TimeUnit.SECONDS), "JavaFX must remain responsive");
    if (failure.get() != null) throw new AssertionError(failure.get());
  }
}
