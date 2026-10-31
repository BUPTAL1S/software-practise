package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real PJSIP callbacks against an isolated UDP peer; no running application or proxy needed. */
class CallConnectedNotificationTest {
  @BeforeAll
  static void initToolkit() {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void answeringOutgoingCallNotifiesUiAfterConfirmedState(boolean video) throws Exception {
    CountDownLatch registered = new CountDownLatch(1);
    CountDownLatch confirmed = new CountDownLatch(1);
    CountDownLatch uiConnected = new CountDownLatch(1);
    AtomicReference<SipService> service = new AtomicReference<>();
    SipService sip = new SipService(new SipService.Events() {
      public void log(String message) {}
      public void log(String category, String message) {
        if (category.equals("注册") && message.contains("200")) registered.countDown();
        if (message.contains("通话已接通确认")) confirmed.countDown();
      }
      public void sip(boolean outgoing, String summary, String raw) {}
      public void stateChanged() {
        SipService current = service.get();
        if (current != null && current.isCallConnected()) uiConnected.countDown();
      }
    });
    service.set(sip);
    try (Peer peer = new Peer()) {
      try {
        sip.start(5094, "127.0.0.1");
        // The fake peer also acknowledges REGISTER, so no shared 1001/1002 registration is changed.
        sip.register("127.0.0.1:" + peer.socket.getLocalPort(), "1098", "", "lab", "udp");
        assertTrue(registered.await(5, TimeUnit.SECONDS), "Isolated registration must succeed");
        sip.call("1099", "lab", video);
        assertTrue(peer.invited.await(5, TimeUnit.SECONDS), "Peer must receive INVITE");
        peer.answer(video);
        assertTrue(confirmed.await(5, TimeUnit.SECONDS), "PJSIP must confirm the answered call");
        assertTrue(sip.isCallConnected(), "Native call must actually be connected");
        if (video) {
          assertTrue(sip.isCurrentCallVideoIntent(), "Audio-only answer must preserve video intent");
          assertFalse(sip.isCurrentCallVideo(), "Rejected video must not be reported as active video");
        }
        assertTrue(uiConnected.await(2, TimeUnit.SECONDS),
            "CONFIRMED must dispatch stateChanged so the caller leaves the waiting screen");
      } finally {
        sip.hangup();
        peer.bye.await(2, TimeUnit.SECONDS);
        sip.shutdown();
      }
    }
  }

  static final class Peer implements AutoCloseable {
    final DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
    final CountDownLatch invited = new CountDownLatch(1);
    final CountDownLatch bye = new CountDownLatch(1);
    final Thread receiver;
    volatile String invite;
    volatile java.net.SocketAddress caller;

    Peer() throws Exception {
      receiver = new Thread(() -> {
        while (!socket.isClosed()) {
          try {
            DatagramPacket packet = new DatagramPacket(new byte[65535], 65535);
            socket.receive(packet);
            String request = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
            if (request.startsWith("REGISTER ") || request.startsWith("BYE ")) {
              respond(request, packet.getSocketAddress(), "200 OK", "");
              if (request.startsWith("BYE ")) bye.countDown();
            } else if (request.startsWith("INFO ") || request.startsWith("OPTIONS ") || request.startsWith("UPDATE ")) {
              // Keyframe requests/session maintenance must not expire the long-running fixture call.
              respond(request, packet.getSocketAddress(), "200 OK", "");
            } else if (request.startsWith("INVITE ")) {
              invite = request;
              caller = packet.getSocketAddress();
              respond(request, caller, "180 Ringing", "");
              invited.countDown();
            }
          } catch (Exception e) {
            if (!socket.isClosed()) throw new RuntimeException(e);
          }
        }
      }, "isolated-sip-peer");
      receiver.setDaemon(true);
      receiver.start();
    }

    void answer(boolean video) throws Exception {
      String sdp = "v=0\r\no=peer 1 1 IN IP4 127.0.0.1\r\ns=test\r\n"
          + "c=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 49000 RTP/AVP 0\r\n"
          + "a=rtpmap:0 PCMU/8000\r\na=sendrecv\r\n";
      // Also cover video-intent calls accepted as audio: both use the same CONFIRMED callback.
      if (video) sdp += "m=video 0 RTP/AVP 97\r\n";
      respond(invite, caller, "200 OK", sdp);
    }

    void answerWithVideo() throws Exception {
      String sdp = "v=0\r\no=peer 1 1 IN IP4 127.0.0.1\r\ns=test\r\n"
          + "c=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 49000 RTP/AVP 0\r\n"
          + "a=rtpmap:0 PCMU/8000\r\na=sendrecv\r\n"
          + "m=video 49002 RTP/AVP 97\r\na=rtpmap:97 H264/90000\r\n";
      for (String line : invite.split("\r\n")) {
        if (line.startsWith("a=fmtp:97 ")) sdp += line + "\r\n";
      }
      respond(invite, caller, "200 OK", sdp + "a=sendrecv\r\n");
    }

    void respond(String request, java.net.SocketAddress address, String status, String body) throws Exception {
      StringBuilder response = new StringBuilder("SIP/2.0 " + status + "\r\n");
      for (String line : request.split("\r\n")) {
        if (line.startsWith("Via:") || line.startsWith("From:") || line.startsWith("Call-ID:")
            || line.startsWith("CSeq:")) response.append(line).append("\r\n");
        if (line.startsWith("To:")) response.append(line)
            .append(line.contains(";tag=") ? "" : ";tag=isolated-peer").append("\r\n");
      }
      response.append("Contact: <sip:1099@127.0.0.1:").append(socket.getLocalPort())
          .append(">\r\nExpires: 3600\r\n");
      if (!body.isEmpty()) response.append("Content-Type: application/sdp\r\n");
      response.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length)
          .append("\r\n\r\n").append(body);
      byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
      socket.send(new DatagramPacket(bytes, bytes.length, address));
    }

    public void close() throws Exception {
      socket.close();
      receiver.join(1000);
    }
  }
}
