package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CapabilitySnapshotCacheTest {
  @Test void tenThousandReadsEnumerateOnceUntilExpiry() {
    AtomicLong clock = new AtomicLong(); AtomicInteger loads = new AtomicInteger();
    var cache = new CapabilitySnapshotCache<Integer>(100, clock::get);
    for (int i=0;i<10000;i++) assertEquals(1,cache.get(loads::incrementAndGet));
    clock.set(100); assertEquals(2,cache.get(loads::incrementAndGet));
    assertEquals(2,loads.get());
  }
  @Test void invalidationDuringEnumerationCannotPublishAnOldSnapshot() {
    var cache = new CapabilitySnapshotCache<Integer>(1000);
    assertEquals(1,cache.get(() -> { cache.invalidate(); return 1; }));
    assertEquals(2,cache.get(() -> 2));
  }
  @Test void explicitInvalidationRefreshesImmediately() {
    AtomicInteger loads = new AtomicInteger(); var cache = new CapabilitySnapshotCache<Integer>(Long.MAX_VALUE);
    assertEquals(1,cache.get(loads::incrementAndGet)); cache.invalidate();
    assertEquals(2,cache.get(loads::incrementAndGet));
  }
  @Test void peekNeverLoadsAndDoesNotReturnExpiredOrInvalidatedEntries() {
    AtomicLong clock = new AtomicLong();
    var cache = new CapabilitySnapshotCache<Integer>(100, clock::get);
    assertNull(cache.peek()); cache.get(() -> 7); assertEquals(7, cache.peek());
    clock.set(100); assertNull(cache.peek());
    cache.get(() -> 8); cache.invalidate(); assertNull(cache.peek());
  }
}
