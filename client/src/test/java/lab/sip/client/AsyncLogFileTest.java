package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class AsyncLogFileTest {
  @Test void blockedDiskCannotBlockProducerAndQueueIsBounded() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
    StringWriter output = new StringWriter();
    AsyncLogFile sink = new AsyncLogFile(4, () -> {
      blocked.countDown();
      try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test release timeout"); }
      catch (InterruptedException error) { throw new IOException(error); }
      return output;
    });
    try {
      assertTrue(blocked.await(1, TimeUnit.SECONDS));
      // The writer is unavailable until after all offers and close have returned.
      for (int i=0; i<10000; i++) sink.offer("record " + i);
      assertEquals(9996, sink.dropped()); sink.close();
      release.countDown(); assertTrue(sink.awaitClosed(2, TimeUnit.SECONDS));
      assertTrue(output.toString().contains("record 0"));
      assertTrue(output.toString().contains("dropped 9996 entries"));
    } finally { release.countDown(); sink.close(); }
  }

  @Test void oversizedEntriesAreTruncatedAndOpenFailureClosesWorker() throws Exception {
    StringWriter output = new StringWriter();
    AsyncLogFile sink = new AsyncLogFile(4, () -> output);
    sink.offer("x".repeat(100000)); sink.close();
    assertTrue(sink.awaitClosed(2, TimeUnit.SECONDS));
    assertTrue(output.toString().endsWith("[truncated]" + System.lineSeparator()));
    assertTrue(output.toString().length() < 33000);
    AsyncLogFile failed = new AsyncLogFile(4, () -> { throw new IOException("disk unavailable"); });
    assertTrue(failed.awaitClosed(2, TimeUnit.SECONDS)); assertNotNull(failed.failure());
    for (int i=0; i<10000; i++) failed.offer("ignored after failure");
    assertEquals(0, failed.dropped());
  }
}
