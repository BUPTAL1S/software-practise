package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;

class CallPreviewLifecycleTest {
  @Test void mainUiDoesNotWaitForEngineLockDuringNativeCameraWork() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    var created = new java.util.concurrent.CompletableFuture<MainApp>();
    Platform.runLater(() -> created.complete(new MainApp()));
    MainApp app = created.get(3, TimeUnit.SECONDS);
    var field = MainApp.class.getDeclaredField("calls"); field.setAccessible(true);
    CallUiCoordinator calls = (CallUiCoordinator) field.get(app);
    var workField = CallUiCoordinator.class.getDeclaredField("callPreviews"); workField.setAccessible(true);
    CallPreviewLifecycle lifecycle = (CallPreviewLifecycle) workField.get(calls);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), pulse = new CountDownLatch(1);
    try {
      lifecycle.execute(() -> { synchronized (app.sip()) { entered.countDown(); release.await(5, TimeUnit.SECONDS); } }, () -> {}, e -> {});
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      Platform.runLater(() -> { app.syncUiState(); pulse.countDown(); });
      assertTrue(pulse.await(500, TimeUnit.MILLISECONDS), "MainApp refresh must skip native/engine queries during camera work");
    } finally {
      release.countDown();
      Platform.runLater(calls::close);
    }
  }

  @Test void timeoutKeepsNativeWorkIsolatedUntilItActuallyReturns() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    CountDownLatch release = new CountDownLatch(1), timeout = new CountDownLatch(1), completed = new CountDownLatch(1);
    try (CallPreviewLifecycle lifecycle = new CallPreviewLifecycle()) {
      try {
        lifecycle.execute(() -> release.await(15, TimeUnit.SECONDS), completed::countDown, error -> timeout.countDown());
        assertTrue(timeout.await(10, TimeUnit.SECONDS));
        assertTrue(lifecycle.isBusy(), "A timeout cannot release a native call that is still running");
        release.countDown();
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertFalse(lifecycle.isBusy());
      } finally { release.countDown(); }
    }
  }

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
