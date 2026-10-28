package lab.sip.client;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountClientTest {
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
