package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NativeVideoPlacementTest {
  private NativeVideoPlacement.Bounds bounds(long remote, long pip, int width, double scale) {
    return new NativeVideoPlacement.Bounds(1, remote, pip, 0, 40, width, 500, scale, scale);
  }

  @Test void pollingUnchangedVideoDoesNotReparentRenderer() {
    NativeVideoPlacement placement = new NativeVideoPlacement();
    AtomicInteger calls = new AtomicInteger();
    for (int i = 0; i < 120; i++) {
      assertTrue(placement.apply(bounds(2, 3, 800, 1), () -> {
        calls.incrementAndGet();
        return true;
      }));
    }
    assertEquals(1, calls.get());
  }

  @Test void resizeDpiAndNewStreamsStillRelayout() {
    NativeVideoPlacement placement = new NativeVideoPlacement();
    AtomicInteger calls = new AtomicInteger();
    for (var next : new NativeVideoPlacement.Bounds[] {
        bounds(2, 3, 800, 1), bounds(2, 3, 900, 1), bounds(2, 3, 900, 1.5),
        bounds(4, 3, 900, 1.5), bounds(4, 5, 900, 1.5), bounds(4, 0, 900, 1.5)}) {
      assertTrue(placement.apply(next, () -> { calls.incrementAndGet(); return true; }));
    }
    assertEquals(6, calls.get());
  }

  @Test void detachAllowsSameHandlesToBeAttachedAgain() {
    NativeVideoPlacement placement = new NativeVideoPlacement();
    assertTrue(placement.apply(bounds(2, 3, 800, 1), () -> true));
    placement.invalidate();
    assertFalse(placement.apply(bounds(2, 3, 800, 1), () -> false));
  }

  @Test void failedNativeAttachIsRetried() {
    NativeVideoPlacement placement = new NativeVideoPlacement();
    assertFalse(placement.apply(bounds(2, 3, 800, 1), () -> false));
    AtomicInteger calls = new AtomicInteger();
    assertTrue(placement.apply(bounds(2, 3, 800, 1), () -> {
      calls.incrementAndGet(); return true;
    }));
    assertEquals(1, calls.get());
  }

  @Test void invalidatingWhileNativeAttachIsBlockedDoesNotBlockUiOrCacheStalePlacement() throws Exception {
    var placement = new NativeVideoPlacement();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var pending = worker.submit(() -> placement.apply(bounds(2, 3, 800, 1), () -> {
        entered.countDown();
        try { return release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
      }));
      assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
      assertTimeoutPreemptively(java.time.Duration.ofMillis(200), placement::invalidate);
      release.countDown();
      pending.get(1, java.util.concurrent.TimeUnit.SECONDS);
      AtomicInteger calls = new AtomicInteger();
      assertTrue(placement.apply(bounds(2, 3, 800, 1), () -> { calls.incrementAndGet(); return true; }));
      assertEquals(1, calls.get(), "A late native result must not suppress the next attach");
    } finally { release.countDown(); worker.shutdownNow(); }
  }
}
