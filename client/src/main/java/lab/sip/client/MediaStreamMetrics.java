package lab.sip.client;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Interval RTP diagnostics; packet rates are deliberately not labelled as decoded video FPS. */
final class MediaStreamMetrics {
  record Sample(long nanos, long txBytes, long rxBytes, long rxPackets, long rxLost,
                double jitterMs, double roundTripMs, long bufferDelayMs, long discarded) {}
  private final Map<Integer, Sample> previous = new HashMap<>();

  String record(int index, String type, String codec, String negotiated, Sample sample) {
    Sample old = previous.put(index, sample);
    String prefix = type + " codec=" + codec + " negotiated=" + negotiated;
    if (old == null || sample.nanos() <= old.nanos() || sample.txBytes() < old.txBytes()
        || sample.rxBytes() < old.rxBytes() || sample.rxPackets() < old.rxPackets()) {
      return prefix + " counters=warmup";
    }
    long elapsed = sample.nanos() - old.nanos();
    long packets = sample.rxPackets() - old.rxPackets();
    long lost = Math.max(0, sample.rxLost() - old.rxLost());
    double loss = packets + lost == 0 ? 0 : 100.0 * lost / (packets + lost);
    return prefix + String.format(Locale.ROOT,
        " interval=%.2fs tx=%.1fkbps rx=%.1fkbps rxLoss=%.2f%% jitterMean=%.2fms rttMean=%.2fms jbuf=%dms discarded=%d",
        elapsed / 1_000_000_000.0, (sample.txBytes() - old.txBytes()) * 8_000_000.0 / elapsed,
        (sample.rxBytes() - old.rxBytes()) * 8_000_000.0 / elapsed, loss, sample.jitterMs(),
        sample.roundTripMs(), sample.bufferDelayMs(), Math.max(0, sample.discarded() - old.discarded()));
  }
}
