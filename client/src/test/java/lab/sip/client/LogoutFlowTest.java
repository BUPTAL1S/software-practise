package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

class LogoutFlowTest {
  @Test void logoutReturnsToEmptyPasswordLoginAndIsolatesRepeatedSessions() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    AtomicInteger logoutRequests = new AtomicInteger();
    AtomicInteger loginRequests = new AtomicInteger();
    HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    backend.createContext("/api/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      if (path.equals("/api/logout")) logoutRequests.incrementAndGet();
      if (path.equals("/api/login")) loginRequests.incrementAndGet();
      byte[] body = (path.equals("/api/health") ? "{\"status\":\"ok\",\"esl\":{\"connected\":false}}"
          : path.equals("/api/logout") ? "{}" : "[]").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    backend.start();
    MainApp root = fx(MainApp::new);
    Stage stage = fx(Stage::new);
    try {
      fx(() -> {
        var start = MainApp.class.getDeclaredMethod("startSession", Stage.class, Map.class, boolean.class);
        start.setAccessible(true);
        start.invoke(root, stage, Map.of("user", "1001", "server", "127.0.0.1",
            "http", "http://127.0.0.1:" + backend.getAddress().getPort()), true);
        return null;
      });
      for (int cycle = 1; cycle <= 2; cycle++) {
        MainApp previous = fx(() -> (MainApp) field(root, "activeSession"));
        if (Boolean.getBoolean("sip.logoutNativeTest")) {
          // Bind a dedicated test port without registering any real account.
          previous.sip().start(15062, "127.0.0.1");
          assertTrue(previous.sip().isStarted());
        }
        int expectedRequests = cycle;
        fx(() -> {
          previous.getHttpFileClient().setAuthToken("test-session-" + expectedRequests);
          previous.getOrCreateSession("1002").add(ChatMessage.text("1001", "1002", true, "private"));
          MainViewAssembler assembler = (MainViewAssembler) field(previous, "assembler");
          Stage settings = (Stage) field(assembler, "settingsModalStage");
          settings.show();
          settings.setHeight(settings.getMinHeight());
          settings.getScene().getRoot().applyCss();
          settings.getScene().getRoot().layout();
          Button logout = (Button) settings.getScene().lookup("#settings-logout");
          var bounds = logout.localToScene(logout.getBoundsInLocal());
          assertTrue(bounds.getMinY() >= 0);
          assertTrue(bounds.getMaxY() <= settings.getScene().getHeight(), "Logout must fit at minimum height");
          logout.fire();
          logout.fire(); // A second click must not start another teardown.
          assertFalse(settings.isShowing());
          return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!fx(() -> stage.getScene().lookup("#login-submit") != null)) {
          assertTrue(System.nanoTime() < deadline, "Logout must return to the login page");
          Thread.sleep(30);
        }
        fx(() -> {
          MainApp current = (MainApp) field(root, "activeSession");
          assertNotSame(previous, current);
          assertNotSame(previous.sip(), current.sip(), "A closed native engine must not be reused");
          assertFalse(previous.sip().isStarted());
          assertNull(previous.getHttpFileClient().getAuthToken());
          assertNull(current.getHttpFileClient().getAuthToken());
          assertTrue(current.getOrCreateSession("1002").isEmpty(), "Previous account data must not leak");
          assertEquals("1001", ((TextField) stage.getScene().lookup("#login-account")).getText());
          assertEquals("", ((PasswordField) stage.getScene().lookup("#login-password")).getText());
          assertFalse(stage.getScene().lookup("#login-submit").isDisabled());
          assertEquals("登录 · SIP Connect", stage.getTitle());
          return null;
        });
        assertEquals(cycle, logoutRequests.get());
        assertEquals(0, loginRequests.get(), "Returning to login must not silently authenticate");
      }
    } finally {
      fx(() -> {
        root.stop();
        for (Window window : new ArrayList<>(Window.getWindows())) window.hide();
        return null;
      });
      backend.stop(0);
    }
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static <T> T fx(java.util.concurrent.Callable<T> work) throws Exception {
    CompletableFuture<T> result = new CompletableFuture<>();
    Platform.runLater(() -> {
      try { result.complete(work.call()); }
      catch (Throwable error) { result.completeExceptionally(error); }
    });
    return result.get(20, TimeUnit.SECONDS);
  }
}
