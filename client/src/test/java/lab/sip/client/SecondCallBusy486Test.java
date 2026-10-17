package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;

class SecondCallBusy486Test {

  @Test
  void testSecondIncomingCallReturns486WithoutCrash() throws Exception {
    // 检查 Kamailio 是否在 127.0.0.1:5060 运行
    boolean kamailioUp = CampusLan.probeTcp("127.0.0.1");
    assumeTrue(kamailioUp, "Kamailio 5060 not running, skip live 486 test");

    // 初始化 JavaFX Toolkit 以便 Platform.runLater 能正常排队
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
      // Toolkit already initialized
    }

    CountDownLatch regLatch = new CountDownLatch(1);
    AtomicBoolean busyLogSeen = new AtomicBoolean(false);

    SipService sip =
        new SipService(
            new SipService.Events() {
              @Override
              public void log(String msg) {
                System.out.println("[SIP-LOG] " + msg);
              }

              @Override
              public void log(String category, String msg) {
                System.out.println("[SIP-LOG " + category + "] " + msg);
                if ("注册".equals(category) && msg.contains("200")) {
                  regLatch.countDown();
                }
                if ("通话".equals(category) && msg.contains("486 Busy Here")) {
                  busyLogSeen.set(true);
                }
              }

              @Override
              public void sip(boolean outgoing, String summary, String raw) {
                System.out.println("[SIP-PKT " + (outgoing ? "OUT" : "IN") + "] " + summary);
              }

              @Override
              public void stateChanged() {}
            });

    int boundPort = sip.start(5074, "127.0.0.1");
    System.out.println("[TEST] SipService started on port " + boundPort);

    try {
      sip.register("127.0.0.1", "1008", "1008", "lab", "udp");
      boolean registered = regLatch.await(5, TimeUnit.SECONDS);
      assertTrue(registered, "1008 注册超时");
      assertTrue(sip.isRegistered(), "1008 应处于已注册状态");

      // 准备 Caller 1 (1002) UDP socket
      int caller1Port = 5084;
      DatagramSocket sock1 = new DatagramSocket(caller1Port, InetAddress.getByName("127.0.0.1"));
      sock1.setSoTimeout(5000);

      // 准备 Caller 2 (1003) UDP socket
      int caller2Port = 5085;
      DatagramSocket sock2 = new DatagramSocket(caller2Port, InetAddress.getByName("127.0.0.1"));
      sock2.setSoTimeout(5000);

      try {
        InetAddress kamailioAddr = InetAddress.getByName("127.0.0.1");
        int kamailioPort = 5060;

        long ts = System.currentTimeMillis();
        String callId1 = "call1-" + ts + "@127.0.0.1";
        String tag1 = "tag1-" + ts;
        String sdp =
            "v=0\r\n"
                + "o=1002 1000 1000 IN IP4 127.0.0.1\r\n"
                + "s=Call1\r\n"
                + "c=IN IP4 127.0.0.1\r\n"
                + "t=0 0\r\n"
                + "m=audio 40010 RTP/AVP 0 101\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n"
                + "a=rtpmap:101 telephone-event/8000\r\n";

        String invite1 =
            "INVITE sip:1008@lab SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:" + caller1Port + ";branch=z9hG4bK-c1-" + ts + ";rport\r\n"
                + "Max-Forwards: 70\r\n"
                + "From: <sip:1002@lab>;tag=" + tag1 + "\r\n"
                + "To: <sip:1008@lab>\r\n"
                + "Call-ID: " + callId1 + "\r\n"
                + "CSeq: 1 INVITE\r\n"
                + "Contact: <sip:1002@127.0.0.1:" + caller1Port + ";transport=udp>\r\n"
                + "Content-Type: application/sdp\r\n"
                + "Content-Length: " + sdp.length() + "\r\n"
                + "\r\n"
                + sdp;

        System.out.println("[TEST] 发送第一路 INVITE (1002 -> 1008)...");
        byte[] buf1 = invite1.getBytes(StandardCharsets.UTF_8);
        sock1.send(new DatagramPacket(buf1, buf1.length, kamailioAddr, kamailioPort));

        // 等待 1008 振铃 (180 Ringing)
        boolean ringing1 = false;
        byte[] recvBuf = new byte[65535];
        while (!ringing1) {
          DatagramPacket p = new DatagramPacket(recvBuf, recvBuf.length);
          sock1.receive(p);
          String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
          System.out.println("[TEST-CALLER1 RECV] " + resp.lines().findFirst().orElse(""));
          if (resp.contains(" 180 Ringing")) {
            ringing1 = true;
          }
        }
        assertTrue(ringing1, "第一路呼叫应当振铃");
        assertTrue(sip.hasIncoming(), "SipService 当前应当处于振铃/有呼叫状态 (activeCall != null)");

        // 此时向 1008 发送第二路 INVITE (1003 -> 1008)
        long ts2 = System.currentTimeMillis();
        String callId2 = "call2-" + ts2 + "@127.0.0.1";
        String tag2 = "tag2-" + ts2;
        String invite2 =
            "INVITE sip:1008@lab SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:" + caller2Port + ";branch=z9hG4bK-c2-" + ts2 + ";rport\r\n"
                + "Max-Forwards: 70\r\n"
                + "From: <sip:1003@lab>;tag=" + tag2 + "\r\n"
                + "To: <sip:1008@lab>\r\n"
                + "Call-ID: " + callId2 + "\r\n"
                + "CSeq: 1 INVITE\r\n"
                + "Contact: <sip:1003@127.0.0.1:" + caller2Port + ";transport=udp>\r\n"
                + "Content-Type: application/sdp\r\n"
                + "Content-Length: " + sdp.length() + "\r\n"
                + "\r\n"
                + sdp;

        System.out.println("[TEST] 发送第二路 INVITE (1003 -> 1001)...");
        byte[] buf2 = invite2.getBytes(StandardCharsets.UTF_8);
        sock2.send(new DatagramPacket(buf2, buf2.length, kamailioAddr, kamailioPort));

        // Caller 2 必须收到 486 Busy Here
        boolean gotBusy486 = false;
        String full486Msg = "";
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline && !gotBusy486) {
          try {
            DatagramPacket p2 = new DatagramPacket(recvBuf, recvBuf.length);
            sock2.receive(p2);
            String resp2 = new String(p2.getData(), 0, p2.getLength(), StandardCharsets.UTF_8);
            System.out.println("[TEST-CALLER2 RECV] " + resp2.lines().findFirst().orElse(""));
            if (resp2.contains(" 486 Busy Here") || resp2.contains(" 486 ")) {
              gotBusy486 = true;
              full486Msg = resp2;
              break;
            }
          } catch (Exception ex) {
            break;
          }
        }

        assertTrue(gotBusy486, "第二路呼叫必须收到 486 Busy Here 响应！");
        System.out.println("[TEST-SUCCESS] 成功捕获 486 Busy 报文:\n" + full486Msg.strip());
        assertTrue(busyLogSeen.get(), "SipService 必须打印 486 回复日志");

        // 给 Platform.runLater 充分时间执行 busyCall.delete()
        Thread.sleep(300);

        // 验证 JVM 依然存活，第一路呼叫依然处于振铃中，未受任何干扰
        assertTrue(sip.isStarted(), "SipService 必须依然健康运行 (未发生 SIGSEGV 崩溃)");
        assertTrue(sip.hasIncoming(), "第一路呼叫必须依然在保持振铃，未被第二路 486 影响");

        // 结束第一路呼叫 (CANCEL)
        String cancel1 =
            "CANCEL sip:1008@lab SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:" + caller1Port + ";branch=z9hG4bK-c1-" + ts + ";rport\r\n"
                + "Max-Forwards: 70\r\n"
                + "From: <sip:1002@lab>;tag=" + tag1 + "\r\n"
                + "To: <sip:1008@lab>\r\n"
                + "Call-ID: " + callId1 + "\r\n"
                + "CSeq: 1 CANCEL\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n";
        byte[] bufCancel = cancel1.getBytes(StandardCharsets.UTF_8);
        sock1.send(new DatagramPacket(bufCancel, bufCancel.length, kamailioAddr, kamailioPort));
        Thread.sleep(300);

      } finally {
        sock1.close();
        sock2.close();
      }
    } finally {
      sip.shutdown();
    }
  }
}
