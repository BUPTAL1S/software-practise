package lab.sip.client;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javafx.application.Platform;

/** Serial native work while JavaFX continues pumping the shared SDL window queue. */
final class CallPreviewLifecycle implements AutoCloseable {
  private static final long NATIVE_TIMEOUT_SECONDS = 8;

  private final AtomicInteger pending = new AtomicInteger();
  private volatile boolean stuck;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "call-preview-lifecycle");
    thread.setDaemon(true);
    return thread;
  });
  private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "call-preview-watchdog");
    thread.setDaemon(true);
    return thread;
  });

  boolean isBusy() { return pending.get() != 0; }

  void start(SipService sip, VideoCallWindow window, Runnable completed, Consumer<Throwable> failed) {
    // Only the local preview window is recreated. Detaching the remote child
    // here invalidates its SDL handle while the call is still using it.
    window.detachPip();
    window.setMediaBusy(true);
    execute(sip::startCallPreview, completed, failed);
  }

  void changeCamera(SipService sip, VideoCallWindow window, boolean enabled,
      Runnable completed, Consumer<Throwable> failed) {
    if (window != null) { window.detachPip(); window.setMediaBusy(true); }
    execute(() -> {
      sip.setCameraEnabled(enabled);
      if (sip.isCallActive()) sip.setCallVideoTransmit(enabled);
      // The call and preview share the capture device: disconnect its transmitter
      // before releasing the preview's device reference, not the other way round.
      if (!enabled && sip.isPreviewActive()) sip.stopPreview();
    }, completed, failed);
  }

  @FunctionalInterface interface Work { void run() throws Exception; }

  void execute(Work work, Runnable completed, Consumer<Throwable> failed) {
    if (stuck) {
      Platform.runLater(() -> failed.accept(
          new IOException("摄像头预览仍未释放，请稍后重试")));
      return;
    }
    pending.incrementAndGet();
    AtomicBoolean finished = new AtomicBoolean();
    ScheduledFuture<?> watchdog = timeouts.schedule(() -> {
      synchronized (this) {
        if (finished.compareAndSet(false, true)) {
          stuck = true;
          Platform.runLater(() -> failed.accept(
              new IOException("摄像头预览超时，原生操作仍在执行，暂时禁止重复操作")));
        }
      }
    }, NATIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    worker.execute(() -> {
      Throwable error = null;
      try {
        work.run();
      } catch (Throwable failure) {
        error = failure;
      }
      final Throwable result = error;
      synchronized (this) {
        finished.set(true);
        watchdog.cancel(false);
        pending.decrementAndGet();
        if (pending.get() == 0) stuck = false;
      }
      // Report completion even after a timeout, so the UI can safely release its busy state.
      Platform.runLater(() -> {
        if (result == null) completed.run(); else failed.accept(result);
      });
    });
  }

  public void close() {
    timeouts.shutdownNow();
    worker.shutdownNow();
  }
}
