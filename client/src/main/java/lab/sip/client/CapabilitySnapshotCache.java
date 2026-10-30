package lab.sip.client;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Short-lived immutable capability snapshots; invalidation never takes a native-work lock. */
final class CapabilitySnapshotCache<T> {
  private record Entry<T>(T value, long created, long generation) {}
  private final long lifetimeNanos;
  private final LongSupplier clock;
  private final AtomicLong generation = new AtomicLong();
  private volatile Entry<T> entry;

  CapabilitySnapshotCache(long lifetimeNanos) { this(lifetimeNanos, System::nanoTime); }
  CapabilitySnapshotCache(long lifetimeNanos, LongSupplier clock) {
    if (lifetimeNanos <= 0) throw new IllegalArgumentException("cache lifetime must be positive");
    this.lifetimeNanos = lifetimeNanos; this.clock = clock;
  }

  T get(Supplier<T> loader) {
    Entry<T> snapshot = entry;
    if (fresh(snapshot)) return snapshot.value();
    synchronized (this) {
      snapshot = entry;
      if (fresh(snapshot)) return snapshot.value();
      long version = generation.get();
      T value = Objects.requireNonNull(loader.get());
      // Engine shutdown/restart can invalidate while a native query is finishing.
      if (version == generation.get()) entry = new Entry<>(value, clock.getAsLong(), version);
      return value;
    }
  }

  /** A non-blocking read; callers can establish their own native-engine lock before loading. */
  T peek() {
    Entry<T> snapshot = entry;
    return fresh(snapshot) ? snapshot.value() : null;
  }

  void invalidate() { generation.incrementAndGet(); entry = null; }

  private boolean fresh(Entry<T> snapshot) {
    return snapshot != null && snapshot.generation() == generation.get()
        && clock.getAsLong() - snapshot.created() < lifetimeNanos;
  }
}
