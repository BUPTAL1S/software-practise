package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ServerDiscoveryTest {
  @Test void staleAddressFindsSameServerAtNewIpAndUpdatesBothPortsWithoutCredentials() throws Exception {
    try (Host host = new Host("127.0.0.2", UUID.randomUUID().toString(), 5092, "sip-connect")) {
      var result = lookup(List.of(host)).find(stale(), host.id);
      assertNotNull(result.server());
      assertEquals("127.0.0.2:5092", result.server().sip());
      assertEquals(host.url(), result.server().http());
      assertEquals(host.id, result.server().id());
      assertEquals(0, host.authRequests.get());
    }
  }

  @Test void validCurrentHttpAlsoCorrectsStaleSipHost() throws Exception {
    try (Host host = new Host("127.0.0.1", UUID.randomUUID().toString(), 5070, "sip-connect")) {
      var result = lookup(List.of()).find(new ConnectionDefaults.Settings("10.0.0.99", host.url()), host.id);
      assertEquals("127.0.0.1:5070", result.server().sip());
    }
  }

  @Test void differentServerOrUnrelatedHttpApplicationIsNotAccepted() throws Exception {
    try (Host other = new Host("127.0.0.1", UUID.randomUUID().toString(), 5060, "sip-connect");
         Host unrelated = new Host("127.0.0.1", UUID.randomUUID().toString(), 5060, "another-app")) {
      assertNull(lookup(List.of(other)).find(stale(), UUID.randomUUID().toString()).server());
      assertNull(lookup(List.of(unrelated)).find(stale(), "").server());
      assertEquals(0, other.authRequests.get() + unrelated.authRequests.get());
    }
  }

  @Test void multipleHostsRequireSelectionButRememberedIdentityDisambiguates() throws Exception {
    try (Host first = new Host("127.0.0.1", UUID.randomUUID().toString(), 5060, "sip-connect");
         Host second = new Host("127.0.0.1", UUID.randomUUID().toString(), 5060, "sip-connect")) {
      var client = lookup(List.of(first, second));
      assertNull(client.find(stale(), "").server());
      assertEquals(second.id, client.find(stale(), second.id).server().id());
    }
  }

  @Test void invalidNonceAndOversizedResponseCannotBecomeAConnection() throws Exception {
    try (Host host = new Host("127.0.0.1", UUID.randomUUID().toString(), 5060, "sip-connect")) {
      host.wrongNonce = true;
      assertNull(lookup(List.of(host)).find(stale(), "").server());
      host.wrongNonce = false; host.large = true;
      assertNull(lookup(List.of(host)).find(stale(), "").server());
    }
  }

  private static ConnectionDefaults.Settings stale() {
    return new ConnectionDefaults.Settings("127.0.0.99:1", "http://127.0.0.99:1");
  }
  private static ServerDiscovery lookup(List<Host> hosts) {
    return new ServerDiscovery(0, 350, hosts.stream().map(h -> new InetSocketAddress(h.ip, h.udp.getLocalPort())).toList());
  }

  static final class Host implements AutoCloseable {
    final String ip, id; final DatagramSocket udp; final HttpServer http;
    final AtomicInteger authRequests = new AtomicInteger();
    volatile boolean wrongNonce, large;
    Host(String ip, String id, int sip, String service) throws Exception {
      this.ip = ip; this.id = id;
      http = HttpServer.create(new InetSocketAddress(ip, 0), 0);
      http.createContext("/api/discovery", exchange -> {
        String nonce = exchange.getRequestURI().getRawQuery().substring("nonce=".length());
        String json = "{\"service\":\"" + service + "\",\"version\":1,\"instanceId\":\"" + id
            + "\",\"httpPort\":" + http.getAddress().getPort() + ",\"sipPort\":" + sip + ",\"nonce\":\"" + nonce + "\"}";
        byte[] bytes = (large ? json + " ".repeat(5000) : json).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
      });
      http.createContext("/", exchange -> { authRequests.incrementAndGet(); exchange.sendResponseHeaders(404, -1); exchange.close(); });
      http.start();
      udp = new DatagramSocket(new InetSocketAddress(ip, 0));
      Thread responder = new Thread(() -> {
        while (!udp.isClosed()) try {
          DatagramPacket packet = new DatagramPacket(new byte[256], 256); udp.receive(packet);
          String query = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII);
          String nonce = wrongNonce ? "0".repeat(32) : query.substring("DISCOVER SIPCONNECT/1 ".length());
          byte[] reply = ("SIPCONNECT/1 " + nonce + " " + id + " " + http.getAddress().getPort() + " " + sip).getBytes(StandardCharsets.US_ASCII);
          udp.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
        } catch (Exception closed) { if (!udp.isClosed()) throw new RuntimeException(closed); }
      });
      responder.setDaemon(true); responder.start();
    }
    String url() { return "http://" + ip + ":" + http.getAddress().getPort(); }
    public void close() { udp.close(); http.stop(0); }
  }
}
