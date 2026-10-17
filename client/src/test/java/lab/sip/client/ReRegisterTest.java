package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;

class ReRegisterTest {

  @Test
  void testReRegisterWhenAlreadyRegistered() throws Exception {
    boolean kamailioUp = CampusLan.probeTcp("127.0.0.1");
    assumeTrue(kamailioUp, "Kamailio not running on 127.0.0.1:5060");

    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
    }

    CountDownLatch firstRegLatch = new CountDownLatch(1);
    CountDownLatch secondRegLatch = new CountDownLatch(1);

    java.util.concurrent.atomic.AtomicReference<SipService> sipRef =
        new java.util.concurrent.atomic.AtomicReference<>();

    SipService sip =
        new SipService(
            new SipService.Events() {
              private int regCount = 0;

              @Override
              public void log(String msg) {
                System.out.println("[SIP-LOG] " + msg);
              }

              @Override
              public void log(String category, String msg) {
                System.out.println("[SIP-LOG " + category + "] " + msg);
                if ("注册".equals(category) && msg.contains("200")) {
                  regCount++;
                  if (regCount == 1) {
                    firstRegLatch.countDown();
                  } else if (regCount == 2) {
                    secondRegLatch.countDown();
                  }
                }
              }

              @Override
              public void sip(boolean outgoing, String summary, String raw) {
                System.out.println("[SIP-PKT " + (outgoing ? "OUT" : "IN") + "] " + summary);
              }

              @Override
              public void stateChanged() {
                SipService s = sipRef.get();
                if (s != null) {
                  System.out.println("[SIP-STATE] " + s.statusText());
                }
              }
            });
    sipRef.set(sip);

    int port = sip.start(5076, "127.0.0.1");
    System.out.println("[TEST] Bound port: " + port);

    try {
      // 第一次注册
      System.out.println("[TEST] Starting First Registration...");
      sip.register("127.0.0.1", "1007", "1007", "lab", "udp");
      assertTrue(firstRegLatch.await(5, TimeUnit.SECONDS), "First registration timed out");
      assertTrue(sip.isRegistered(), "First registration should be OK");
      assertEquals("已注册  代理 sip:127.0.0.1:5060;transport=udp", sip.statusText());

      // 模拟用户再次点击“一键上线”或“注册/上线”
      System.out.println("[TEST] Starting Second Registration (clicking register again)...");
      sip.register("127.0.0.1", "1007", "1007", "lab", "udp");

      // 观察 statusText() 是否变成 "已启动，未注册"！
      System.out.println("[TEST] Immediate status after clicking register again: " + sip.statusText());

      boolean secondRegSuccess = secondRegLatch.await(3, TimeUnit.SECONDS);
      System.out.println("[TEST] Second reg success: " + secondRegSuccess + ", statusText: " + sip.statusText());
      assertTrue(sip.isRegistered(), "Must remain or become registered after clicking register again!");

      // 模拟用户快速连续点击 3 次
      System.out.println("[TEST] Testing rapid repeated clicks...");
      for (int i = 0; i < 3; i++) {
        sip.register("127.0.0.1", "1007", "1007", "lab", "udp");
        Thread.sleep(20);
      }
      Thread.sleep(600);
      assertTrue(sip.isRegistered(), "Must remain registered after rapid clicks without falling into unregistered!");
      assertTrue(sip.statusText().contains("已注册"), "Status text must say 已注册");
    } finally {
      sip.shutdown();
    }
  }
}
