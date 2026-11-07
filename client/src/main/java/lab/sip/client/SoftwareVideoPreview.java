package lab.sip.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

/** A file-only software player for Windows machines whose JavaFX media backend cannot play an attachment. */
final class SoftwareVideoPreview implements AutoCloseable {
  private static final AudioFormat PCM = new AudioFormat(48_000, 16, 2, true, false);
  private static final long NO_SEEK = -1;
  private final AtomicReference<Picture> newest = new AtomicReference<>();
  private final AtomicReference<byte[]> spare = new AtomicReference<>();
  private final CompletableFuture<Void> stopped = new CompletableFuture<>();
  private final Thread decoder;
  private volatile boolean closed;
  private volatile boolean playing;
  private volatile boolean ended;
  private volatile boolean muted;
  private volatile long duration;
  private volatile long lastFrameTime;
  private volatile long position;
  private volatile Throwable failure;
  private long requestedSeek;
  private long generation;
  private long anchorTime;
  private long anchorPosition;

  record Picture(int width, int height, int stride, byte[] pixels) {}
  private record Request(long seek, long generation) {}

  static boolean supportedPlatform() {
    String system = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String architecture = System.getProperty("os.arch", "");
    return system.startsWith("windows") && (architecture.equals("amd64") || architecture.equals("x86_64"));
  }

  SoftwareVideoPreview(Path source) {
    decoder = new Thread(() -> decode(source), "attachment-software-decoder");
    decoder.setDaemon(true);
    decoder.start();
  }

  synchronized void setPlaying(boolean value) {
    if (closed || playing == value) return;
    if (value && ended) seek(0);
    playing = value;
    resetClock();
    notifyAll();
  }

  synchronized void seek(double seconds) {
    if (closed) return;
    requestedSeek = Math.max(0, Math.min(lastFrameTime, Math.round(seconds * 1_000_000)));
    generation++;
    ended = false;
    notifyAll();
  }

  void setMuted(boolean value) { muted = value; }
  boolean isPlaying() { return playing; }
  boolean isEnded() { return ended; }
  double durationSeconds() { return duration / 1_000_000.0; }
  double positionSeconds() { return position / 1_000_000.0; }
  Throwable failure() { return failure; }
  Picture takePicture() { return newest.getAndSet(null); }
  void recycle(Picture picture) { spare.compareAndSet(null, picture.pixels()); }
  CompletableFuture<Void> stopped() { return stopped; }

  private void decode(Path source) {
    SourceDataLine audio = null;
    try (var input = new FFmpegFrameGrabber(source.toAbsolutePath().toString())) {
      // A chat attachment is a local MP4-family file, never a playlist or external reference.
      input.setFormat("mov");
      input.setOption("protocol_whitelist", "file");
      input.setOption("enable_drefs", "0");
      input.setVideoOption("max_pixels", "40000000");
      input.setVideoOption("threads", "2");
      input.setPixelFormat(AV_PIX_FMT_BGRA);
      input.setSampleFormat(AV_SAMPLE_FMT_S16);
      input.setSampleRate((int) PCM.getSampleRate());
      input.setAudioChannels(PCM.getChannels());
      input.start();
      int width = input.getImageWidth(), height = input.getImageHeight();
      duration = input.getLengthInTime();
      if (!input.hasVideo() || width <= 0 || height <= 0 || duration <= 0) {
        throw new IOException("视频轨道或时长无法识别");
      }
      double scale = Math.min(1, Math.min(1280.0 / width, 720.0 / height));
      input.setImageWidth(Math.max(1, (int) (width * scale)));
      input.setImageHeight(Math.max(1, (int) (height * scale)));
      lastFrameTime = Math.max(0, duration - Math.round(1_000_000.0 / Math.max(1, input.getVideoFrameRate())));
      if (input.hasAudio() && !closed) audio = openAudio();

      boolean positioned = false;
      for (Request request = nextRequest(); request != null; request = nextRequest()) {
        if (request.seek() != NO_SEEK) {
          if (audio != null) audio.flush();
          if (positioned) input.setVideoTimestamp(request.seek());
          Frame still = input.grabImage();
          if (still == null) throw new IOException("无法读取预览画面");
          positioned = true;
          if (acceptSeek(still.timestamp, request.generation())) publish(still);
          continue;
        }
        Frame frame = input.grab();
        if (frame == null) {
          finish(request.generation());
          continue;
        }
        if (!awaitFrame(frame.timestamp, request.generation())) continue;
        if (frame.image != null) publish(frame);
        if (audio != null && !muted && frame.samples != null) playSamples(audio, frame);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception | LinkageError unavailable) {
      if (!closed) failure = unavailable;
    } finally {
      try { if (audio != null) audio.close(); }
      finally { stopped.complete(null); }
    }
  }

  private synchronized Request nextRequest() throws InterruptedException {
    while (!closed && !playing && requestedSeek == NO_SEEK) wait();
    if (closed) return null;
    Request request = new Request(requestedSeek, generation);
    requestedSeek = NO_SEEK;
    return request;
  }

  private synchronized boolean acceptSeek(long timestamp, long expectedGeneration) {
    if (closed || generation != expectedGeneration) return false;
    position = Math.max(0, Math.min(duration, timestamp));
    resetClock();
    return true;
  }

  private synchronized boolean awaitFrame(long timestamp, long expectedGeneration) throws InterruptedException {
    long framePosition = Math.max(position, Math.min(duration, timestamp));
    while (!closed && generation == expectedGeneration) {
      if (!playing) { wait(); continue; }
      long remaining = TimeUnit.MICROSECONDS.toNanos(framePosition - anchorPosition)
          - (System.nanoTime() - anchorTime);
      if (remaining <= 0) {
        position = framePosition;
        return true;
      }
      TimeUnit.NANOSECONDS.timedWait(this, remaining);
    }
    return false;
  }

  private synchronized void finish(long expectedGeneration) {
    if (closed || generation != expectedGeneration) return;
    position = duration;
    playing = false;
    ended = true;
  }

  private void resetClock() {
    anchorPosition = position;
    anchorTime = System.nanoTime();
  }

  private void publish(Frame frame) {
    int length = (frame.imageHeight - 1) * frame.imageStride + frame.imageWidth * 4;
    byte[] bytes = spare.getAndSet(null);
    if (bytes == null || bytes.length < length) bytes = new byte[length];
    ByteBuffer source = ((ByteBuffer) frame.image[0]).duplicate();
    source.clear();
    source.get(bytes, 0, length);
    Picture skipped = newest.getAndSet(new Picture(frame.imageWidth, frame.imageHeight, frame.imageStride, bytes));
    if (skipped != null) recycle(skipped);
  }

  private static SourceDataLine openAudio() {
    SourceDataLine line = null;
    try {
      line = AudioSystem.getSourceDataLine(PCM);
      line.open(PCM, 8192);
      line.start();
      return line;
    } catch (LineUnavailableException | IllegalArgumentException unavailable) {
      if (line != null) line.close();
      System.getLogger(SoftwareVideoPreview.class.getName()).log(System.Logger.Level.WARNING,
          "Video preview audio output unavailable: " + unavailable.getMessage());
      return null;
    }
  }

  private static void playSamples(SourceDataLine audio, Frame frame) {
    if (!(frame.samples[0] instanceof ShortBuffer buffer)) return;
    ShortBuffer samples = buffer.duplicate();
    int length = samples.remaining() * Short.BYTES;
    if (length == 0 || length > audio.available()) return;
    ByteBuffer bytes = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    bytes.asShortBuffer().put(samples);
    audio.write(bytes.array(), 0, length);
  }

  @Override public synchronized void close() {
    closed = true;
    decoder.interrupt();
    notifyAll();
  }
}
