package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercise the actual incoming-window answer worker in an isolated JVM: a native assert must fail the test. */
class IncomingCallWorkerTest {
  @org.junit.jupiter.api.Test
  void shutdownDrainsCallsEvenWhenFxCleanupIsDelayed() throws Exception {
    runProbe(DelayedCleanupProbe.class, "cleanup", false);
  }

  public static class DelayedCleanupProbe {
    public static void main(String[] args) throws Exception {
      Platform.startup(() -> {}); Platform.setImplicitExit(false);
      CountDownLatch releaseUi = new CountDownLatch(1), uiBlocked = new CountDownLatch(1);
      Platform.runLater(() -> { uiBlocked.countDown(); try { releaseUi.await(20, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
      if (!uiBlocked.await(2, TimeUnit.SECONDS)) throw new AssertionError("UI not blocked");
      CountDownLatch registered = new CountDownLatch(1);
      var connected = new java.util.concurrent.atomic.AtomicInteger();
      SipService sip = new SipService(new SipService.Events() {
        public void log(String message) {}
        public void log(String category, String message) {
          if (category.equals("注册") && message.contains("200")) registered.countDown();
          if (message.contains("通话已接通确认")) connected.incrementAndGet();
        }
        public void sip(boolean outgoing, String summary, String raw) {}
        public void stateChanged() {}
      });
      try (CallConnectedNotificationTest.Peer peer = new CallConnectedNotificationTest.Peer()) {
        sip.start(15988, "127.0.0.1");
        sip.register("127.0.0.1:" + peer.socket.getLocalPort(), "1098", "", "lab", "udp");
        if (!registered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Registration missing");
        for (int index = 1; index <= 3; index++) {
          String previous = peer.invite;
          sip.call("1099", "lab", false);
          long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
          while (peer.invite == previous && System.nanoTime() < until) Thread.sleep(10);
          if (peer.invite == previous) throw new AssertionError("INVITE missing");
          peer.answer(false);
          while (connected.get() < index && System.nanoTime() < until) Thread.sleep(10);
          if (connected.get() < index) throw new AssertionError("Call not connected");
          sip.hangup();
          while (sip.isInCall() && System.nanoTime() < until) Thread.sleep(10);
          if (sip.isInCall()) throw new AssertionError("Call not disconnected");
        }
        sip.shutdown();
        CountDownLatch uiDrained = new CountDownLatch(1);
        Platform.runLater(uiDrained::countDown);
        releaseUi.countDown();
        if (!uiDrained.await(3, TimeUnit.SECONDS)) throw new AssertionError("Delayed cleanup not drained");
        System.gc(); System.runFinalization();
        System.out.println("PASS cleanup worker");
      } finally { releaseUi.countDown(); Platform.exit(); }
      System.exit(0);
    }
  }
  @org.junit.jupiter.api.Test
  void cameraControlMessageCanUseAFreshThread() throws Exception {
    runProbe(MessageProbe.class, "message", false);
  }

  public static class MessageProbe {
    public static void main(String[] args) throws Exception {
      CountDownLatch registered = new CountDownLatch(1);
      CountDownLatch delivered = new CountDownLatch(1);
      SipService sip = new SipService(new SipService.Events() {
        public void log(String message) {}
        public void log(String category, String message) {
          if (category.equals("注册") && message.contains("200")) registered.countDown();
        }
        public void sip(boolean outgoing, String summary, String raw) {}
        public void stateChanged() {}
        public void instantMessageStatus(String to, String body, int code, String reason) {
          if (code == 200) delivered.countDown();
        }
      });
      try (Peer peer = new Peer()) {
        sip.start(15986, "127.0.0.1");
        sip.register("127.0.0.1:" + peer.socket.getLocalPort(), "1098", "", "lab", "udp");
        if (!registered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Registration missing");
        CompletableFuture<Void> sent = new CompletableFuture<>();
        new Thread(() -> {
          try { sip.sendMessage("1099", "lab", SipService.VIDEO_AVATAR_OFF); sent.complete(null); }
          catch (Throwable error) { sent.completeExceptionally(error); }
        }, "camera-avatar-control").start();
        sent.get(3, TimeUnit.SECONDS);
        if (!peer.message.await(3, TimeUnit.SECONDS)) throw new AssertionError("Camera control MESSAGE missing");
        if (!delivered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Camera control MESSAGE was not acknowledged");
        sip.shutdown();
        System.out.println("PASS message worker");
      }
      System.exit(0);
    }
  }
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void incomingCallCanBeAnsweredByUiWorkerWithoutAborting(boolean videoOffer) throws Exception {
    runProbe(Probe.class, "incoming", videoOffer);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void outgoingCallCanUseAFreshControlThread(boolean video) throws Exception {
    runProbe(OutgoingProbe.class, "outgoing", video);
  }

  private void runProbe(Class<?> probe, String direction, boolean videoOffer) throws Exception {
    Path log = Path.of("target/" + direction + "-worker-" + videoOffer + ".log").toAbsolutePath();
    String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process child = new ProcessBuilder(java, "-cp", cp, probe.getName(), Boolean.toString(videoOffer))
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      for (int i = 0; i < 300 && child.isAlive(); i++) {
        if (Files.exists(log)) {
          String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
          assertFalse(output.contains("Calling pjlib from unknown/external thread"), output);
        }
        child.waitFor(100, TimeUnit.MILLISECONDS);
      }
      assertFalse(child.isAlive(), "Incoming worker timed out: " + log);
      String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
      assertEquals(0, child.exitValue(), output);
      assertTrue(output.contains("PASS " + direction + " worker"), output);
    } finally { if (child.isAlive()) child.destroyForcibly(); }
  }

  public static class OutgoingProbe {
    public static void main(String[] args) throws Exception {
      Platform.startup(() -> {}); Platform.setImplicitExit(false);
      CountDownLatch registered = new CountDownLatch(1), connected = new CountDownLatch(1);
      SipService sip = new SipService(new SipService.Events() {
        public void log(String message) {}
        public void log(String category, String message) {
          if (category.equals("注册") && message.contains("200")) registered.countDown();
          if (message.contains("通话已接通确认")) connected.countDown();
        }
        public void sip(boolean outgoing, String summary, String raw) {}
        public void stateChanged() {}
      });
      try (CallConnectedNotificationTest.Peer peer = new CallConnectedNotificationTest.Peer()) {
        sip.start(15984, "127.0.0.1");
        var registration = new CompletableFuture<Void>();
        new Thread(() -> {
          try { sip.register("127.0.0.1:" + peer.socket.getLocalPort(), "1098", "", "lab", "udp"); registration.complete(null); }
          catch (Throwable failure) { registration.completeExceptionally(failure); }
        }, "register-worker").start();
        registration.get(3, TimeUnit.SECONDS);
        if (!registered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Registration missing");
        CallUiCoordinator ui = new CallUiCoordinator(host(sip));
        boolean video = Boolean.parseBoolean(args[0]);
        Platform.runLater(() -> ui.dial("1099", "lab", video));
        if (!peer.invited.await(4, TimeUnit.SECONDS)) throw new AssertionError("Outgoing INVITE missing");
        if (video) peer.answerWithVideo(); else peer.answer(false);
        if (!connected.await(4, TimeUnit.SECONDS)) throw new AssertionError("Outgoing call never connected");
        Platform.runLater(ui::hangup);
        if (!peer.bye.await(3, TimeUnit.SECONDS)) throw new AssertionError("UI hangup never sent BYE");
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Platform.runLater(() -> { ui.close(); closed.complete(null); });
        closed.get(3, TimeUnit.SECONDS);
        sip.shutdown();
        System.out.println("PASS outgoing worker");
      } finally { Platform.exit(); }
      System.exit(0);
    }
  }

  private static CallUiCoordinator.Host host(SipService sip) {
    return new CallUiCoordinator.Host() {
      public javafx.stage.Stage owner() { return null; }
      public SipService sip() { return sip; }
      public LogView logs() { return null; }
      public MainViewAssembler assembler() { return null; }
      public HttpFileClient http() { return new HttpFileClient("http://127.0.0.1:1"); }
      public String domain() { return "lab"; }
      public String user() { return "1098"; }
      public void runAction(Throwing action) { try { action.run(); } catch (Exception e) { throw new RuntimeException(e); } }
      public void refreshMainUi() {}
    };
  }

  public static class Probe {
    public static void main(String[] args) throws Exception {
      Platform.startup(() -> {}); Platform.setImplicitExit(false);
      CountDownLatch registered = new CountDownLatch(1);
      SipService sip = new SipService(new SipService.Events() {
        public void log(String message) { System.out.println(message); }
        public void log(String category, String message) {
          System.out.println(category + ": " + message);
          if (category.equals("注册") && message.contains("200")) registered.countDown();
        }
        public void sip(boolean outgoing, String summary, String raw) {}
        public void stateChanged() { }
      });
      try (Peer peer = new Peer()) {
        int port = sip.start(15982, "127.0.0.1");
        sip.register("127.0.0.1:" + peer.socket.getLocalPort(), "1098", "", "lab", "udp");
        if (!registered.await(4, TimeUnit.SECONDS)) throw new AssertionError("registration missing");
        peer.invite(port, Boolean.parseBoolean(args[0]));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!sip.hasIncoming()) {
          if (System.nanoTime() > until) throw new AssertionError("incoming notification missing");
          Thread.sleep(10);
        }
        // The service was initialized on main, while the actual answer runs on call-sip-control.
        CallUiCoordinator ui = new CallUiCoordinator(new CallUiCoordinator.Host() {
          public javafx.stage.Stage owner() { return null; }
          public SipService sip() { return sip; }
          public LogView logs() { return null; }
          public MainViewAssembler assembler() { return null; }
          public HttpFileClient http() { return new HttpFileClient("http://127.0.0.1:1"); }
          public String domain() { return "lab"; }
          public String user() { return "1098"; }
          public void runAction(Throwing action) { try { action.run(); } catch (Exception e) { throw new RuntimeException(e); } }
          public void refreshMainUi() {}
        });
        if (!peer.ringing.await(3, TimeUnit.SECONDS)) throw new AssertionError("incoming 180 missing");
        // Also exercise metadata reads on a fresh Java thread, independent of startup's FX registration.
        CompletableFuture<Void> metadata = new CompletableFuture<>();
        new Thread(() -> {
          try {
            if (!sip.hasIncoming() || sip.currentCallTarget().isBlank() || sip.getActiveCallRemoteUri().isBlank())
              throw new AssertionError("incoming details missing");
            sip.isIncomingVideo(); sip.statusText();
            metadata.complete(null);
          } catch (Throwable error) { metadata.completeExceptionally(error); }
        }, "incoming-details").start();
        metadata.get(3, TimeUnit.SECONDS);
        Platform.runLater(() -> ui.answer(Boolean.parseBoolean(args[0])));
        if (!peer.answered.await(5, TimeUnit.SECONDS)) throw new AssertionError("answer worker never sent 200 OK");
        sip.hangup();
        Thread.sleep(150);
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Platform.runLater(() -> { ui.close(); closed.complete(null); });
        closed.get(3, TimeUnit.SECONDS);
        sip.shutdown();
        System.out.println("PASS incoming worker");
      } finally { Platform.exit(); }
      System.exit(0);
    }
  }

  static class Peer implements AutoCloseable {
    final DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
    final CountDownLatch answered = new CountDownLatch(1);
    final CountDownLatch ringing = new CountDownLatch(1);
    final CountDownLatch message = new CountDownLatch(1);
    final Thread worker;
    int port;
    Peer() throws Exception {
      worker = new Thread(() -> {
        while (!socket.isClosed()) try {
          byte[] bytes = new byte[16000]; DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
          socket.receive(packet);
          String message = new String(bytes, 0, packet.getLength(), StandardCharsets.UTF_8);
          if (message.startsWith("REGISTER ") || message.startsWith("BYE ") || message.startsWith("MESSAGE ")) {
            StringBuilder reply = new StringBuilder("SIP/2.0 200 OK\r\n");
            for (String line : message.split("\r\n"))
              if (line.startsWith("Via:") || line.startsWith("From:") || line.startsWith("To:")
                  || line.startsWith("Call-ID:") || line.startsWith("CSeq:")) reply.append(line).append("\r\n");
            send(reply + "Expires: 3600\r\nContent-Length: 0\r\n\r\n", packet.getSocketAddress());
            if (message.startsWith("MESSAGE ")) this.message.countDown();
          } else if (message.startsWith("SIP/2.0 180")) {
            ringing.countDown();
          } else if (message.startsWith("SIP/2.0 200") && message.contains("CSeq: 1 INVITE")) {
            String to = message.lines().filter(line -> line.startsWith("To:")).findFirst().orElseThrow();
            send("ACK sip:1098@127.0.0.1:" + port + " SIP/2.0\r\n" + headers(to, "ACK")
                + "Content-Length: 0\r\n\r\n", packet.getSocketAddress());
            answered.countDown();
          }
        } catch (Exception stopped) { if (!socket.isClosed()) stopped.printStackTrace(); }
      });
      worker.setDaemon(true); worker.start();
    }
    String headers(String to, String method) {
      return "Via: SIP/2.0/UDP 127.0.0.1:" + socket.getLocalPort() + ";branch=z9hG4bK-" + method + "\r\n"
          + "From: <sip:1099@lab>;tag=fixture\r\n" + to + "\r\nCall-ID: incoming-worker-test\r\n"
          + "CSeq: 1 " + method + "\r\nContact: <sip:1099@127.0.0.1:" + socket.getLocalPort() + ">\r\nMax-Forwards: 70\r\n";
    }
    void invite(int port, boolean video) throws Exception {
      this.port = port;
      String sdp = "v=0\r\no=fixture 1 1 IN IP4 127.0.0.1\r\ns=fixture\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\n"
          + "m=audio 49000 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=sendrecv\r\n"
          + (video ? "m=video 49002 RTP/AVP 97\r\na=rtpmap:97 H264/90000\r\na=sendrecv\r\n" : "");
      send("INVITE sip:1098@127.0.0.1:" + port + " SIP/2.0\r\n" + headers("To: <sip:1098@lab>", "INVITE")
          + "Content-Type: application/sdp\r\nContent-Length: " + sdp.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + sdp,
          new InetSocketAddress("127.0.0.1", port));
    }
    void send(String message, SocketAddress destination) throws Exception {
      byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
      socket.send(new DatagramPacket(bytes, bytes.length, destination));
    }
    public void close() throws Exception { socket.close(); worker.join(1000); }
  }
}
