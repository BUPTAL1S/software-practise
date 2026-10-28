package lab.sip.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javafx.application.Platform;

/** Serial native work while JavaFX continues pumping the shared SDL window queue. */
final class CallPreviewLifecycle implements AutoCloseable {
  private final AtomicInteger pending = new AtomicInteger();
  private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "call-preview-lifecycle");
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
    pending.incrementAndGet();
    worker.execute(() -> {
      Throwable error = null;
      try { work.run(); } catch (Throwable failure) { error = failure; }
      Throwable result = error;
      Platform.runLater(() -> {
        pending.decrementAndGet();
        if (result == null) completed.run(); else failed.accept(result);
      });
    });
  }

  public void close() { worker.shutdown(); }
}
