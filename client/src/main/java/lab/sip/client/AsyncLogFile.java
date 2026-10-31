package lab.sip.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Optional diagnostic logging must never perform disk I/O on a native media callback. */
final class AsyncLogFile implements AutoCloseable {
  interface Opener { Writer open() throws IOException; }
  private final ArrayBlockingQueue<String> pending;
  private final AtomicBoolean closing = new AtomicBoolean();
  private final AtomicLong dropped = new AtomicLong();
  private final CountDownLatch stopped = new CountDownLatch(1);
  private volatile IOException failure;

  static AsyncLogFile fromEnvironment() {
    String path = System.getenv("UI_LOG_FILE");
    if (path == null || path.isBlank()) return null;
    return new AsyncLogFile(256, () -> Files.newBufferedWriter(Path.of(path), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND));
  }

  AsyncLogFile(int capacity, Opener opener) {
    pending = new ArrayBlockingQueue<>(capacity);
    Thread worker = new Thread(() -> drain(opener), "sip-diagnostic-file");
    worker.setDaemon(true); worker.start();
  }

  void offer(String message) {
    if (closing.get()) return;
    // Bound retained memory as well as entry count, including unexpectedly large SIP dumps.
    String bounded = message.length() > 32768 ? message.substring(0, 32768) + " [truncated]" : message;
    if (!pending.offer(bounded)) dropped.incrementAndGet();
  }

  private void drain(Opener opener) {
    try (Writer output = opener.open()) {
      long reported = 0;
      while (!closing.get() || !pending.isEmpty()) {
        String line = pending.poll(200, TimeUnit.MILLISECONDS);
        if (line != null) { output.write(line); output.write(System.lineSeparator()); }
        long total = dropped.get();
        if (total != reported) {
          output.write("[diagnostic log queue dropped " + (total - reported) + " entries]" + System.lineSeparator());
          reported = total;
        }
        if (line == null || pending.isEmpty()) output.flush();
      }
    } catch (IOException error) { failure = error; }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    finally { closing.set(true); pending.clear(); stopped.countDown(); }
  }

  long dropped() { return dropped.get(); }
  IOException failure() { return failure; }
  boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException { return stopped.await(timeout, unit); }
  /** Non-blocking even when the disk is slow; the daemon drains the bounded queue. */
  @Override public void close() { closing.set(true); }
}
