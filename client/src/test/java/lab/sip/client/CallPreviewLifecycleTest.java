package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;

class CallPreviewLifecycleTest {
  @Test void blockedNativeWorkDoesNotBlockFxAndQueuedWorkKeepsBusyState() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    CountDownLatch heartbeat = new CountDownLatch(1), finished = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
    try (CallPreviewLifecycle lifecycle = new CallPreviewLifecycle()) {
      lifecycle.execute(() -> { entered.countDown(); release.await(3, TimeUnit.SECONDS); },
          () -> {}, failure::set);
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertTrue(lifecycle.isBusy());
      Platform.runLater(heartbeat::countDown);
      assertTrue(heartbeat.await(1, TimeUnit.SECONDS), "JavaFX must keep pumping while native work is blocked");
      lifecycle.execute(() -> {}, finished::countDown, error -> { failure.set(error); finished.countDown(); });
      release.countDown();
      assertTrue(finished.await(3, TimeUnit.SECONDS));
      assertFalse(lifecycle.isBusy());
      assertNull(failure.get());
    } finally { release.countDown(); }
  }
}
