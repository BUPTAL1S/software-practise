package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import org.junit.jupiter.api.Test;

class IncomingRingtoneTest {
  @Test
  void repeatedNotificationsPlayOnceAndNextCallGetsItsOwnClip() throws Exception {
    RecordedClip first = new RecordedClip();
    RecordedClip second = new RecordedClip();
    AtomicInteger opens = new AtomicInteger();
    AtomicReference<Exception> failure = new AtomicReference<>();
    try (IncomingRingtone ringtone = new IncomingRingtone(
        () -> opens.getAndIncrement() == 0 ? first.clip : second.clip, failure::set)) {
      Object call = new Object();
      for (int i = 0; i < 30; i++) ringtone.update(call);
      assertTrue(first.started.await(2, TimeUnit.SECONDS));
      ringtone.update(null);
      assertTrue(first.closed.await(2, TimeUnit.SECONDS));
      ringtone.update(new Object());
      assertTrue(second.started.await(2, TimeUnit.SECONDS));
    }
    assertTrue(second.closed.await(2, TimeUnit.SECONDS));
    assertEquals(2, opens.get());
    assertEquals(1, first.starts.get());
    assertEquals(1, second.starts.get());
    assertNull(failure.get());
  }

  @Test
  void cancellationDuringDeviceOpenPreventsDelayedPlayback() throws Exception {
    RecordedClip clip = new RecordedClip();
    CountDownLatch opening = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Exception> failure = new AtomicReference<>();
    try (IncomingRingtone ringtone = new IncomingRingtone(() -> {
      opening.countDown();
      assertTrue(release.await(3, TimeUnit.SECONDS));
      return clip.clip;
    }, failure::set)) {
      ringtone.update(new Object());
      assertTrue(opening.await(2, TimeUnit.SECONDS));
      assertTimeoutPreemptively(java.time.Duration.ofMillis(500), () -> ringtone.update(null));
      release.countDown();
      assertTrue(clip.closed.await(2, TimeUnit.SECONDS));
      assertEquals(0, clip.starts.get());
      assertNull(failure.get());
    } finally { release.countDown(); }
  }

  @Test
  void newCallReplacesAnObsoleteDeviceOpen() throws Exception {
    RecordedClip first = new RecordedClip();
    RecordedClip second = new RecordedClip();
    CountDownLatch opening = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger opens = new AtomicInteger();
    AtomicReference<Exception> failure = new AtomicReference<>();
    try (IncomingRingtone ringtone = new IncomingRingtone(() -> {
      if (opens.getAndIncrement() == 0) {
        opening.countDown();
        assertTrue(release.await(3, TimeUnit.SECONDS));
        return first.clip;
      }
      return second.clip;
    }, failure::set)) {
      ringtone.update(new Object());
      assertTrue(opening.await(2, TimeUnit.SECONDS));
      ringtone.update(new Object());
      release.countDown();
      assertTrue(second.started.await(2, TimeUnit.SECONDS));
      assertTrue(first.closed.await(2, TimeUnit.SECONDS));
      assertEquals(0, first.starts.get());
      assertEquals(1, second.starts.get());
      assertNull(failure.get());
    } finally { release.countDown(); }
    assertTrue(second.closed.await(2, TimeUnit.SECONDS));
  }

  @Test
  void closeDuringOpenReleasesDeviceAndRejectsLaterNotifications() throws Exception {
    RecordedClip clip = new RecordedClip();
    CountDownLatch opening = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger opens = new AtomicInteger();
    IncomingRingtone ringtone = new IncomingRingtone(() -> {
      opens.incrementAndGet();
      opening.countDown();
      assertTrue(release.await(3, TimeUnit.SECONDS));
      return clip.clip;
    }, error -> fail(error));
    try {
      ringtone.update(new Object());
      assertTrue(opening.await(2, TimeUnit.SECONDS));
      assertTimeoutPreemptively(java.time.Duration.ofMillis(500), ringtone::close);
      ringtone.update(new Object());
      release.countDown();
      assertTrue(clip.closed.await(2, TimeUnit.SECONDS));
      assertEquals(0, clip.starts.get());
      assertEquals(1, opens.get());
    } finally { release.countDown(); ringtone.close(); }
  }

  @Test
  void unavailableDeviceIsReportedOncePerCallAndLaterCallsCanPlay() throws Exception {
    RecordedClip clip = new RecordedClip();
    AtomicInteger opens = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    CountDownLatch failed = new CountDownLatch(1);
    try (IncomingRingtone ringtone = new IncomingRingtone(() -> {
      if (opens.incrementAndGet() == 1) throw new LineUnavailableException("Test device unavailable");
      return clip.clip;
    }, error -> { failures.incrementAndGet(); failed.countDown(); })) {
      Object firstCall = new Object();
      ringtone.update(firstCall);
      assertTrue(failed.await(2, TimeUnit.SECONDS));
      for (int i = 0; i < 30; i++) ringtone.update(firstCall);
      ringtone.update(new Object());
      assertTrue(clip.started.await(2, TimeUnit.SECONDS));
      assertEquals(1, failures.get());
      assertEquals(2, opens.get());
    }
    assertTrue(clip.closed.await(2, TimeUnit.SECONDS));
  }

  @Test
  void bundledRingtoneIsReadablePcmAudio() throws Exception {
    var resource = IncomingRingtone.class.getResource("/sounds/incoming-call.wav");
    assertNotNull(resource);
    try (var audio = AudioSystem.getAudioInputStream(resource)) {
      assertEquals(16, audio.getFormat().getSampleSizeInBits());
      assertEquals(1, audio.getFormat().getChannels());
      assertTrue(audio.getFrameLength() > audio.getFormat().getFrameRate());
    }
  }

  static final class RecordedClip {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    final AtomicInteger starts = new AtomicInteger();
    final Clip clip = (Clip) Proxy.newProxyInstance(Clip.class.getClassLoader(), new Class<?>[] {Clip.class},
        (proxy, method, arguments) -> {
          switch (method.getName()) {
            case "loop" -> {
              assertEquals(Clip.LOOP_CONTINUOUSLY, arguments[0]);
              starts.incrementAndGet();
              started.countDown();
            }
            case "close" -> closed.countDown();
            default -> { }
          }
          return null;
        });
  }
}
