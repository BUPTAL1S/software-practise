package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

class DirectorySyncTest {
  @Test void contactsSynchronizeOnChatTabAndTransientFailureDoesNotClearThem() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    AtomicReference<String> directory = new AtomicReference<>(users("1001", "1007", "1008", "1009"));
    AtomicInteger status = new AtomicInteger(200), failuresSeen = new AtomicInteger();
    HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    backend.createContext("/api/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      boolean isDirectory = path.equals("/api/users");
      int code = isDirectory ? status.get() : 200;
      if (code != 200) failuresSeen.incrementAndGet();
      String body = isDirectory ? directory.get()
          : path.equals("/api/health") ? "{\"status\":\"ok\",\"esl\":{\"connected\":false}}"
          : path.equals("/api/login") ? "{\"token\":\"test-token\",\"role\":\"ADMIN\"}" : "[]";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(code, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
    backend.start();
    MainApp app = fx(MainApp::new);
    Stage stage = fx(Stage::new);
    try {
      fx(() -> {
        var start = MainApp.class.getDeclaredMethod("startSession", Stage.class, Map.class, boolean.class);
        start.setAccessible(true);
        // A deliberately unresolvable SIP host: this test only talks to its private HTTP fixture.
        start.invoke(app, stage, Map.of("user", "1001", "server", "127.0.0.1:15999",
            "http", "http://127.0.0.1:" + backend.getAddress().getPort()), false);
        return null;
      });
      await(() -> app.getAssembler().getContactCard("1008") != null
          && app.getAssembler().getContactCard("1009") != null);
      assertEquals(0, fx(() -> app.getRightTabPane().getSelectionModel().getSelectedIndex()));

      status.set(503);
      await(() -> failuresSeen.get() > 0);
      fx(() -> {
        assertNotNull(app.getAssembler().getContactCard("1008"));
        assertNotNull(app.getAssembler().getContactCard("1009"));
        return null;
      });
      status.set(200);
      directory.set(users("1001", "1007", "1009"));
      await(() -> app.getAssembler().getContactCard("1008") == null);
      fx(() -> {
        assertNotNull(app.getAssembler().getContactCard("1007"));
        assertNotNull(app.getAssembler().getContactCard("1009"));
        return null;
      });
      directory.set(users("1001", "1007"));
      await(() -> app.getAssembler().getContactCard("1009") == null);
      assertNotNull(fx(() -> app.getAssembler().getContactCard("1007")));

      directory.set("[]");
      await(() -> app.getAssembler().getContactTargetIds().isEmpty());
      assertEquals(0, fx(() -> app.getRightTabPane().getSelectionModel().getSelectedIndex()));
    } finally {
      fx(() -> { app.stop(); stage.close(); return null; });
      backend.stop(0);
    }
  }

  private static String users(String... names) {
    return java.util.Arrays.stream(names).map(name -> "{\"username\":\"" + name
        + "\",\"sipId\":\"" + name + "\"}").collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!fx(condition)) {
      assertTrue(System.nanoTime() < deadline, "Periodic directory synchronization timed out");
      Thread.sleep(30);
    }
  }

  private static <T> T fx(java.util.concurrent.Callable<T> work) throws Exception {
    CompletableFuture<T> result = new CompletableFuture<>();
    Platform.runLater(() -> {
      try { result.complete(work.call()); }
      catch (Throwable error) { result.completeExceptionally(error); }
    });
    return result.get(15, TimeUnit.SECONDS);
  }
}
