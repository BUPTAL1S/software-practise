package lab.sip.client;

import java.util.function.BooleanSupplier;

/** Avoid reparenting live renderers on every layout poll. Failed attempts remain retryable. */
final class NativeVideoPlacement {
  record Bounds(long parent, long remote, long pip, int x, int y, int w, int h,
                double scaleX, double scaleY) {}

  private Bounds attached;

  boolean apply(Bounds requested, BooleanSupplier attach) {
    if (requested.equals(attached)) return true;
    if (!attach.getAsBoolean()) return false;
    attached = requested;
    return true;
  }

  void invalidate() {
    attached = null;
  }
}
