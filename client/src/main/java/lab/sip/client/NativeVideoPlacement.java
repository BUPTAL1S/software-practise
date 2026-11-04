package lab.sip.client;

import java.util.function.BooleanSupplier;

/** Avoid reparenting live renderers on every layout poll. Failed attempts remain retryable. */
final class NativeVideoPlacement {
  record Bounds(long parent, long remote, long pip, int x, int y, int w, int h,
                double scaleX, double scaleY) {}

  private record Applied(Bounds bounds, long generation) {}
  private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
  private volatile Applied attached;

  boolean apply(Bounds requested, BooleanSupplier attach) {
    long version = generation.get();
    Applied previous = attached;
    if (previous != null && version == previous.generation && requested.equals(previous.bounds)) return true;
    if (!attach.getAsBoolean()) return false;
    if (version != generation.get()) return false;
    attached = new Applied(requested, version);
    return true;
  }

  void invalidate() {
    generation.incrementAndGet(); attached = null;
  }
}
