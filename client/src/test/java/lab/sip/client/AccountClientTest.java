package lab.sip.client;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountClientTest {
  @Test void registrationSendsChosenCredentialsAndUsesCreatedSession() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var request = new java.util.concurrent.atomic.AtomicReference<String>();
    server.createContext("/api/register", exchange -> {
      request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = "{\"token\":\"registered-token\",\"username\":\"0123\",\"sipId\":\"0123\"}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(201, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    try {
      HttpFileClient client = new HttpFileClient("http://127.0.0.1:" + server.getAddress().getPort());
      assertEquals("registered-token", client.register("0123", "chosen\"\\\t密码"));
      assertEquals("{\"username\":\"0123\",\"password\":\"chosen\\\"\\\\\\t密码\"}", request.get());
      assertEquals("registered-token", client.getAuthToken());
      assertEquals("0123", client.getNickname());
      assertEquals("USER", client.getRole());
    } finally { server.stop(0); }
  }

  @Test void registrationConflictReturnsAnActionableErrorWithoutLoggingIn() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/register", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] body = "{\"error\":\"username already exists\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(409, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    try {
      HttpFileClient client = new HttpFileClient("http://127.0.0.1:" + server.getAddress().getPort());
      var error = assertThrows(java.io.IOException.class, () -> client.register("1001", "chosen"));
      assertTrue(error.getMessage().contains("账号已存在"));
      assertNull(client.getAuthToken());
    } finally { server.stop(0); }
  }

  @Test void accountEndpointsRotateTokenAndPreserveQuotedNicknames() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    List<String> auth = new ArrayList<>();
    List<String> methods = new ArrayList<>();
    String nickname = "成员 \"A\" } \\n";
    String profile = "{\"nickname\":\"成员 \\\"A\\\" } \\\\n\",\"role\":\"USER\"}";
    server.createContext("/api/", exchange -> {
      auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
      methods.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
      exchange.getRequestBody().readAllBytes();
      String path = exchange.getRequestURI().getPath();
      String body = path.endsWith("password") ? "{\"token\":\"rotated-token\"}"
          : path.endsWith("users") ? "[{\"id\":2,\"username\":\"1002\",\"sipId\":\"1002\","
              + profile.substring(1) + "]" : profile;
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
    server.start();
    try {
      HttpFileClient client = new HttpFileClient("http://127.0.0.1:" + server.getAddress().getPort());
      client.setAuthToken("initial-token");
      client.refreshProfile();
      assertEquals(nickname, client.getNickname());
      client.changePassword("old", "replacement");
      assertEquals("rotated-token", client.getAuthToken());
      client.updateNickname(nickname);
      assertEquals(nickname, client.fetchUsers().get(0).nickname());
      assertEquals(List.of("Bearer initial-token", "Bearer initial-token", "Bearer rotated-token", "Bearer rotated-token"), auth);
      assertEquals(List.of("GET /api/me", "POST /api/me/password", "PATCH /api/me/profile", "GET /api/users"), methods);
    } finally { server.stop(0); }
  }
}
