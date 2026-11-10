package lab.sip.client;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;

/** Bounded LAN lookup; credentials and media never enter this protocol. */
final class ServerDiscovery {
  static final int PORT = 39771;
  static final String GROUP = "239.255.71.80";
  record Server(String id, String sip, String http) {}
  record Result(Server server, String message) {}
  @FunctionalInterface interface Lookup {
    Result find(ConnectionDefaults.Settings preferred, String rememberedId);
  }
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(450))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private final int port;
  private final long searchMillis;
  private final List<InetSocketAddress> explicitTargets;

  ServerDiscovery() { this(PORT, 1300, null); }
  ServerDiscovery(int port, long searchMillis, List<InetSocketAddress> targets) {
    this.port = port; this.searchMillis = searchMillis; this.explicitTargets = targets;
  }

  Result find(ConnectionDefaults.Settings preferred, String rememberedId) {
    String id = rememberedId == null ? "" : rememberedId;
    String nonce = UUID.randomUUID().toString().replace("-", "");
    Server current = verify(preferred.http(), nonce, null, null);
    if (current != null && (id.isEmpty() || id.equals(current.id())))
      return new Result(current, "已找到服务器");
    List<Server> candidates = new ArrayList<>();
    try (MulticastSocket socket = new MulticastSocket()) {
      socket.setBroadcast(true); socket.setTimeToLive(1); socket.setSoTimeout(120);
      byte[] query = ("DISCOVER SIPCONNECT/1 " + nonce).getBytes(StandardCharsets.US_ASCII);
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(searchMillis), nextSend = 0;
      Set<String> checked = new HashSet<>();
      while (System.nanoTime() < deadline && checked.size() < 12 && !Thread.currentThread().isInterrupted()) {
        if (System.nanoTime() >= nextSend) {
          sendQueries(socket, query); nextSend = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(450);
        }
        DatagramPacket reply = new DatagramPacket(new byte[512], 512);
        try { socket.receive(reply); } catch (SocketTimeoutException wait) { continue; }
        String[] parts = new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.US_ASCII).split(" ");
        if (parts.length != 5 || !parts[0].equals("SIPCONNECT/1") || !parts[1].equals(nonce)
            || !validId(parts[2]) || !id.isEmpty() && !id.equals(parts[2])
            || !(reply.getAddress() instanceof Inet4Address)) continue;
        int http = parsePort(parts[3]), sip = parsePort(parts[4]);
        if (http == 0 || sip == 0) continue;
        String host = reply.getAddress().getHostAddress();
        String url = "http://" + host + ":" + http;
        if (!checked.add(url + "/" + parts[2])) continue;
        Server server = verify(url, nonce, parts[2], sip);
        if (server != null && candidates.stream().noneMatch(s -> s.id().equals(server.id()))) candidates.add(server);
      }
    } catch (IOException unavailableNetwork) { /* Preserve manual connection fields. */ }
    if (candidates.size() == 1) return new Result(candidates.get(0), "已找到服务器");
    if (candidates.size() > 1) return new Result(null, "发现多个服务器，请展开连接设置手动选择");
    return new Result(null, id.isEmpty() ? "未发现服务器，可重新搜索或手动设置" : "未找到上次的服务器，可重新搜索或手动设置");
  }

  private void sendQueries(MulticastSocket socket, byte[] query) throws IOException {
    if (explicitTargets != null) {
      for (InetSocketAddress address : explicitTargets) send(socket, query, address);
      return;
    }
    send(socket, query, new InetSocketAddress("127.0.0.1", port));
    for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      try {
        if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()) continue;
        String name = (nic.getName() + " " + nic.getDisplayName()).toLowerCase(Locale.ROOT);
        if (name.matches(".*(docker|vmware|vmnet|vbox|hyper-v|vethernet|bridge|utun|awdl|veth|tap|tun).*")) continue;
        for (InterfaceAddress address : nic.getInterfaceAddresses()) {
          if (address.getAddress() instanceof Inet4Address && address.getBroadcast() != null)
            send(socket, query, new InetSocketAddress(address.getBroadcast(), port));
        }
        if (nic.supportsMulticast()) {
          socket.setNetworkInterface(nic);
          send(socket, query, new InetSocketAddress(GROUP, port));
        }
      } catch (IOException disconnectedInterface) { /* Try the remaining interfaces. */ }
    }
  }

  private static void send(DatagramSocket socket, byte[] query, InetSocketAddress target) {
    try { socket.send(new DatagramPacket(query, query.length, target)); }
    catch (IOException ignored) { /* A single failed interface must not abort lookup. */ }
  }

  private static Server verify(String url, String nonce, String expectedId, Integer expectedSip) {
    try {
      URI base = URI.create(url);
      if ((!"http".equalsIgnoreCase(base.getScheme()) && !"https".equalsIgnoreCase(base.getScheme()))
          || base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null
          || base.getRawFragment() != null || base.getPath() != null && !base.getPath().isEmpty() && !base.getPath().equals("/")) return null;
      String origin = base.getScheme() + "://" + base.getRawAuthority();
      HttpRequest request = HttpRequest.newBuilder(URI.create(origin + "/api/discovery?nonce=" + nonce))
          .timeout(Duration.ofMillis(650)).header("Accept", "application/json").GET().build();
      HttpResponse<byte[]> response = HTTP.send(request, info -> new LimitedBody());
      if (response.statusCode() != 200) return null;
      String body = new String(response.body(), StandardCharsets.UTF_8);
      String service = field(body, "service"), identity = field(body, "instanceId");
      int version = number(body, "version"), sip = number(body, "sipPort"), http = number(body, "httpPort");
      if (!"sip-connect".equals(service) || version != 1 || !nonce.equals(field(body, "nonce"))
          || !validId(identity) || expectedId != null && !expectedId.equals(identity)
          || expectedSip != null && expectedSip != sip || sip < 1 || sip > 65535 || http < 1 || http > 65535) return null;
      String host = base.getHost();
      return new Server(identity, host + (sip == 5060 ? "" : ":" + sip), origin);
    } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return null; }
    catch (Exception invalidOrOffline) { return null; }
  }

  private static String field(String json, String key) {
    var m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([a-zA-Z0-9_-]+)\"").matcher(json);
    return m.find() ? m.group(1) : "";
  }
  private static int number(String json, String key) {
    var m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)(?=\\s*[,}])").matcher(json);
    return m.find() ? parsePort(m.group(1)) : 0;
  }
  private static int parsePort(String value) {
    try { int n = Integer.parseInt(value); return n > 0 && n <= 65535 ? n : 0; }
    catch (NumberFormatException invalid) { return 0; }
  }
  static boolean validId(String id) {
    return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }
  /** Connection-only diagnostic for packaged runtimes; does not start JavaFX/JNI or authenticate. */
  public static void main(String[] args) {
    String previous = args.length > 0 ? args[0] : "http://127.0.0.1:1";
    String identity = args.length > 1 ? args[1] : "";
    int udpPort = args.length > 2 ? Integer.parseInt(args[2]) : PORT;
    List<InetSocketAddress> targets = args.length > 3
        ? List.of(new InetSocketAddress(args[3], udpPort)) : null;
    Result result = new ServerDiscovery(udpPort, 1300, targets)
        .find(new ConnectionDefaults.Settings("127.0.0.1", previous), identity);
    if (result.server() == null) { System.err.println("DISCOVERY_NOT_FOUND: " + result.message()); System.exit(2); }
    System.out.println("DISCOVERY_FOUND id=" + result.server().id() + " sip=" + result.server().sip() + " http=" + result.server().http());
  }
  private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    final CompletableFuture<byte[]> result = new CompletableFuture<>();
    final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (bytes.size() + buffer.remaining() > 4096) { subscription.cancel(); result.completeExceptionally(new IOException("Discovery response too large")); return; }
        byte[] next = new byte[buffer.remaining()]; buffer.get(next); bytes.writeBytes(next);
      }
      subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(bytes.toByteArray()); }
  }
}
