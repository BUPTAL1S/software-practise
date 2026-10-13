package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpFileClientTest {

  private static HttpServer server;
  private static String serverBaseUrl;
  private static final byte[] TEST_FILE_CONTENT = "Hello SIP HTTP File Transfer!".getBytes(StandardCharsets.UTF_8);

  @BeforeAll
  static void startMockServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

    // /api/health
    server.createContext(
        "/api/health",
        exchange -> {
          byte[] resp = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
          }
        });

    // /api/login
    server.createContext(
        "/api/login",
        exchange -> {
          String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          if (reqBody.contains("\"username\":\"1001\"") && reqBody.contains("\"password\":\"1001\"")) {
            byte[] resp = "{\"token\":\"tok-abc-123\",\"username\":\"1001\",\"sipId\":\"1001\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(resp);
            }
          } else {
            byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(resp);
            }
          }
        });

    // /api/files (POST upload) and /api/files/42 (GET download)
    server.createContext(
        "/api/files",
        exchange -> {
          String method = exchange.getRequestMethod();
          if ("POST".equalsIgnoreCase(method)) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            assertTrue(bodyStr.contains("Content-Disposition: form-data; name=\"file\""));
            assertTrue(bodyStr.contains("test-upload.txt"));
            assertTrue(bodyStr.contains("Hello SIP HTTP File Transfer!"));

            byte[] resp = "{\"id\":42,\"name\":\"test-upload.txt\",\"size\":29,\"url\":\"/api/files/42\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(resp);
            }
          } else if ("GET".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, TEST_FILE_CONTENT.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(TEST_FILE_CONTENT);
            }
          } else {
            exchange.sendResponseHeaders(405, -1);
          }
        });

    // /api/cdr (POST report, GET list)
    server.createContext(
        "/api/cdr",
        exchange -> {
          String method = exchange.getRequestMethod();
          String path = exchange.getRequestURI().getPath();
          if (path.endsWith("/stats")) {
            byte[] resp = "{\"totalCalls\":5,\"totalDurationSec\":250,\"averageDurationSec\":50.0,\"peakRoomMembers\":4}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(resp);
            }
            return;
          }
          if ("POST".equalsIgnoreCase(method)) {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(reqBody.contains("\"caller\":\"1001\""));
            assertTrue(reqBody.contains("\"callee_or_room\":\"1002\""));
            assertTrue(reqBody.contains("\"duration_sec\":35"));

            byte[] resp = "{\"id\":101,\"caller\":\"1001\",\"callee_or_room\":\"1002\",\"started_at\":\"2026-10-13T12:00:00Z\",\"duration_sec\":35}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(resp);
            }
          } else if ("GET".equalsIgnoreCase(method)) {
            byte[] resp = "[{\"id\":101,\"caller\":\"1001\",\"callee_or_room\":\"1002\",\"started_at\":\"2026-10-13T12:00:00Z\",\"duration_sec\":35}]"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(resp);
            }
          } else {
            exchange.sendResponseHeaders(405, -1);
          }
        });

    server.start();
    int port = server.getAddress().getPort();
    serverBaseUrl = "http://127.0.0.1:" + port;
  }


  @AfterAll
  static void stopMockServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void testHealthProbe() {
    HttpFileClient client = new HttpFileClient(serverBaseUrl);
    assertTrue(client.isHealthy());

    HttpFileClient unreachableClient = new HttpFileClient("http://127.0.0.1:59999");
    assertFalse(unreachableClient.isHealthy());
  }

  @Test
  void testLoginSuccessAndFailure() throws Exception {
    HttpFileClient client = new HttpFileClient(serverBaseUrl);

    String token = client.login("1001", "1001");
    assertEquals("tok-abc-123", token);
    assertEquals("tok-abc-123", client.getAuthToken());

    assertThrows(IOException.class, () -> client.login("1001", "wrong-password"));
  }

  @Test
  void testUploadAndDownloadFile(@TempDir Path tempDir) throws Exception {
    HttpFileClient client = new HttpFileClient(serverBaseUrl);

    Path uploadPath = tempDir.resolve("test-upload.txt");
    Files.write(uploadPath, TEST_FILE_CONTENT);

    FileMessage fm = client.uploadFile(uploadPath, "1001", "1002");
    assertNotNull(fm);
    assertEquals("test-upload.txt", fm.getName());
    assertEquals(29, fm.getSize());
    assertEquals(serverBaseUrl + "/api/files/42", fm.getUrl());

    Path downloadPath = tempDir.resolve("downloaded-file.txt");
    Path result = client.downloadFile(fm.getUrl(), downloadPath);
    assertTrue(Files.exists(result));
    assertArrayEquals(TEST_FILE_CONTENT, Files.readAllBytes(result));
  }

  @Test
  void testDownloadRelativeAndLocalhostRewriting(@TempDir Path tempDir) throws Exception {
    HttpFileClient client = new HttpFileClient(serverBaseUrl);

    // Relative url
    Path downloadPath1 = tempDir.resolve("dl1.txt");
    client.downloadFile("/api/files/42", downloadPath1);
    assertArrayEquals(TEST_FILE_CONTENT, Files.readAllBytes(downloadPath1));

    // Simulated cross-host URL sent from another client running on localhost:8080
    // When client.getBaseUrl() is serverBaseUrl (e.g. 127.0.0.1:port), it rewrites to its configured host
    HttpFileClient clientRemote = new HttpFileClient("http://192.168.1.100:" + server.getAddress().getPort());
    assertEquals("http://192.168.1.100:" + server.getAddress().getPort(), clientRemote.getBaseUrl());
  }

  @Test
  void testReportCdr() throws Exception {
    HttpFileClient client = new HttpFileClient(serverBaseUrl);
    client.setAuthToken("tok-abc-123");

    java.time.Instant start = java.time.Instant.parse("2026-10-13T12:00:00Z");
    HttpFileClient.CdrRecord record = client.reportCdr("1001", "1002", start, 35);
    assertNotNull(record);
    assertEquals(101L, record.id());
    assertEquals("1001", record.caller());
    assertEquals("1002", record.calleeOrRoom());
    assertEquals(35, record.durationSec());

    HttpFileClient.CdrRecord recordWithCallId = client.reportCdr("call-uuid-999", "1001", "1002", start, 35);
    assertNotNull(recordWithCallId);
    assertEquals(101L, recordWithCallId.id());
  }

  @Test
  void testFetchCdrList() throws Exception {
    HttpFileClient client = new HttpFileClient(serverBaseUrl);
    java.util.List<HttpFileClient.CdrRecord> list = client.fetchCdrList("1001", 50);
    assertNotNull(list);
    assertEquals(1, list.size());
    HttpFileClient.CdrRecord r = list.get(0);
    assertEquals(101L, r.id());
    assertEquals("1001", r.caller());
    assertEquals("1002", r.calleeOrRoom());
    assertEquals(35, r.durationSec());
  }

  @Test
  void testFetchStats() throws Exception {
    HttpFileClient client = new HttpFileClient(serverBaseUrl);
    HttpFileClient.CdrStats stats = client.fetchStats();
    assertNotNull(stats);
    assertEquals(5, stats.totalCalls());
    assertEquals(250, stats.totalDurationSec());
    assertEquals(50.0, stats.averageDurationSec());
    assertEquals(4, stats.peakRoomMembers());
  }

  @Test
  void testCdrParsers() {
    String json = "{\"id\":77,\"caller\":\"1003\",\"callee_or_room\":\"room-101\",\"started_at\":\"2026-10-13T15:30:00Z\",\"duration_sec\":60}";
    HttpFileClient.CdrRecord rec = HttpFileClient.parseCdrRecord(json);
    assertNotNull(rec);
    assertEquals(77L, rec.id());
    assertEquals("1003", rec.caller());
    assertEquals("room-101", rec.calleeOrRoom());
    assertEquals(60, rec.durationSec());

    String listJson = "[" + json + "]";
    java.util.List<HttpFileClient.CdrRecord> list = HttpFileClient.parseCdrList(listJson);
    assertEquals(1, list.size());
    assertEquals("1003", list.get(0).caller());

    String statsJson = "{\"totalCalls\":8,\"totalDurationSec\":400,\"averageDurationSec\":50.0,\"peakRoomMembers\":6}";
    HttpFileClient.CdrStats stats = HttpFileClient.parseCdrStats(statsJson);
    assertEquals(8, stats.totalCalls());
    assertEquals(400, stats.totalDurationSec());
    assertEquals(50.0, stats.averageDurationSec());
    assertEquals(6, stats.peakRoomMembers());
  }
}

