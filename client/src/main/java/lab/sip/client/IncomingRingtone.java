package lab.sip.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

/** Plays a local notification independently of the SIP and camera workers. */
final class IncomingRingtone implements AutoCloseable {
  private record Request(Object call) {}

  private final Callable<Clip> openClip;
  private final Consumer<Exception> failed;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "incoming-ringtone");
    thread.setDaemon(true);
    return thread;
  });
  private volatile Request requested;
  private volatile boolean closed;
  // Only the audio worker touches the playback device.
  private Clip playing;

  IncomingRingtone(Consumer<Exception> failed) {
    this(IncomingRingtone::loadClip, failed);
  }

  IncomingRingtone(Callable<Clip> openClip, Consumer<Exception> failed) {
    this.openClip = openClip;
    this.failed = failed;
  }

  synchronized void update(Object incomingCall) {
    if (closed || (requested == null ? incomingCall == null : requested.call() == incomingCall)) {
      return;
    }
    Request next = incomingCall == null ? null : new Request(incomingCall);
    requested = next;
    if (next == null) worker.execute(this::stopPlaying);
    else worker.execute(() -> play(next));
  }

  private boolean isCurrent(Request request) {
    return !closed && requested == request;
  }

  private void play(Request request) {
    if (!isCurrent(request)) return;
    stopPlaying();
    try {
      playing = openClip.call();
      // Loading an audio device can finish after an answer, cancellation or logout.
      if (isCurrent(request)) playing.loop(Clip.LOOP_CONTINUOUSLY);
      if (!isCurrent(request)) stopPlaying();
    } catch (Exception error) {
      stopPlaying();
      if (isCurrent(request)) failed.accept(error);
    }
  }

  private void stopPlaying() {
    Clip previous = playing;
    playing = null;
    if (previous == null) return;
    try {
      try { previous.stop(); }
      finally { previous.close(); }
    } catch (Exception error) {
      if (!closed) failed.accept(error);
    }
  }

  private static Clip loadClip() throws Exception {
    try (InputStream input = IncomingRingtone.class.getResourceAsStream("/sounds/incoming-call.wav")) {
      if (input == null) throw new IOException("找不到来电铃声文件");
      try (AudioInputStream audio = AudioSystem.getAudioInputStream(new BufferedInputStream(input))) {
        Clip clip = AudioSystem.getClip();
        try {
          clip.open(audio);
          return clip;
        } catch (Exception error) {
          clip.close();
          throw error;
        }
      }
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    requested = null;
    // Let an in-flight open finish and release its device before the worker exits.
    worker.execute(this::stopPlaying);
    worker.shutdown();
  }
}
