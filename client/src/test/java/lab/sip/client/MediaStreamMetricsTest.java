package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MediaStreamMetricsTest {
  private static MediaStreamMetrics.Sample sample(long time, long bytes, long packets, long lost) {
    return new MediaStreamMetrics.Sample(time, bytes, bytes / 2, packets, lost, 2.5, 12.0, 40, 0);
  }
  @Test void ratesUseIntervalBytesAndCorrectUnits() {
    var metrics = new MediaStreamMetrics();
    assertTrue(metrics.record(0,"audio","PCMU","8000Hz",sample(1,0,0,0)).endsWith("counters=warmup"));
    String line = metrics.record(0,"audio","PCMU","8000Hz",sample(5_000_000_001L,40000,95,5));
    assertTrue(line.contains("tx=64.0kbps rx=32.0kbps rxLoss=5.00%"));
    assertTrue(line.contains("jitterMean=2.50ms rttMean=12.00ms jbuf=40ms"));
  }
  @Test void counterResetsAndIndependentStreamsDoNotInventRates() {
    var metrics = new MediaStreamMetrics();
    metrics.record(0,"audio","PCMU","",sample(1,1000,5,1));
    assertTrue(metrics.record(0,"audio","PCMU","",sample(2,0,0,0)).contains("warmup"));
    assertTrue(metrics.record(1,"video","H264","",sample(2,1000,5,1)).contains("warmup"));
    assertFalse(metrics.record(1,"video","H264","",sample(5_000_000_002L,2000,10,1)).contains("NaN"));
  }
}
