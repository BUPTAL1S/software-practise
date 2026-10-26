package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CallRoomCrashReproTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
    }
    Platform.setImplicitExit(false);
  }

  @Test
  void testCallRoom101Repro() throws Exception {
    boolean kamailioUp = CampusLan.probeTcp("127.0.0.1");
    assumeTrue(kamailioUp, "Kamailio 5060 not running, skip live call room crash repro test");

    CountDownLatch regLatch = new CountDownLatch(1);
    CountDownLatch callLatch = new CountDownLatch(1);

    SipService sip = new SipService(new SipService.Events() {
      @Override public void log(String msg) { System.out.println("[LOG] " + msg); }
      @Override public void log(String category, String msg) {
        System.out.println("[LOG " + category + "] " + msg);
        if ("注册".equals(category) && msg.contains("200")) {
          regLatch.countDown();
        }
        if ("通话".equals(category) && (msg.contains("接通") || msg.contains("确认") || msg.contains("视频流已接通"))) {
          callLatch.countDown();
        }
      }
      @Override public void sip(boolean outgoing, String summary, String raw) {
        System.out.println("[SIP " + (outgoing ? "OUT" : "IN") + "] " + summary);
        if (!outgoing && raw.contains("200 OK")) {
          System.out.println("[SIP RAW 200 OK]\n" + raw);
        }
      }
      @Override public void stateChanged() {}
      @Override public void callEnded(String callId, String caller, String callee, java.time.Instant startedAt, long dur, boolean conn) {}
      @Override public void instantMessageReceived(String from, String to, String body, String ct) {}
      @Override public void instantMessageStatus(String to, String body, int status, String reason) {}
    });

    try {
      sip.start(5084, "127.0.0.1");
      sip.register("127.0.0.1", "1001", "1001", "lab", "udp");
      boolean regOk = regLatch.await(4, TimeUnit.SECONDS);
      assertTrue(regOk, "Registration timed out");

      System.out.println("[TEST] Calling room-101 with video=true...");
      sip.call("room-101", "lab", true);

      boolean callOk = callLatch.await(5, TimeUnit.SECONDS);
      assertTrue(callOk, "Conference call should connect successfully");
      Thread.sleep(1000);
      sip.hangup();
      Thread.sleep(500);
    } finally {
      sip.shutdown();
    }
  }
}
