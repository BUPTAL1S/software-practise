package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class WindowLayoutNativeTest {
  @Test void invalidWindowsAreNotCachedAsSuccessfullyAttached() {
    assertTrue(WindowLayout.isAvailable(), "Build callwindow.dll before this native test");
    assertFalse(WindowLayout.attach(1, 2, 0, 0, 0, 640, 360));
  }

  @Test void exactUnicodeTitleCanBeFoundRepeatedly() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    CompletableFuture<Void> done = new CompletableFuture<>();
    Platform.runLater(() -> {
      Stage stage = new Stage();
      try {
        for (int index = 0; index < 50; index++) {
          String title = "摄像头窗口 🎥 " + UUID.randomUUID() + "-" + index;
          stage.setTitle(title); stage.show();
          assertNotEquals(0, WindowLayout.findWindow(title));
          assertEquals(0, WindowLayout.findWindow(title + " missing"));
        }
        done.complete(null);
      } catch (Throwable error) { done.completeExceptionally(error); }
      finally { stage.close(); }
    });
    done.get(10, TimeUnit.SECONDS);
  }
}
