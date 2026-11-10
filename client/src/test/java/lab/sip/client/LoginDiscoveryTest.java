package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LoginDiscoveryTest {
  @BeforeAll static void startFx() {
    try { Platform.startup(() -> {}); } catch (IllegalStateException running) { }
    Platform.setImplicitExit(false);
  }
  private static <T> T fx(Callable<T> work) throws Exception {
    CompletableFuture<T> done = new CompletableFuture<>();
    Platform.runLater(() -> { try { done.complete(work.call()); } catch (Throwable t) { done.completeExceptionally(t); } });
    return done.get(5, TimeUnit.SECONDS);
  }
  private static void waitFor(Callable<Boolean> condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do { if (fx(condition)) return; Thread.sleep(10); } while (System.nanoTime() < until);
    fail("Discovery UI did not complete");
  }
  private static javafx.scene.Node control(LoginView view, String selector) {
    var node = view.scene().lookup(selector);
    if (node == null) node = ((TitledPane)view.scene().lookup("#login-connections")).getContent().lookup(selector);
    return node;
  }
  private static void show(LoginView view) {
    Stage stage = new Stage();stage.setScene(view.scene());stage.show();
    view.scene().getRoot().applyCss();view.scene().getRoot().layout();
  }
  private static void close(LoginView view) { view.closeDiscovery(); ((Stage)view.scene().getWindow()).close(); }
  private static TextField sip(LoginView view) { return (TextField)control(view,"#login-sip-server"); }
  private static Button submit(LoginView view) { return (Button)view.scene().lookup("#login-submit"); }
  private static void fill(LoginView view) {
    ((TextField)view.scene().lookup("#login-account")).setText("1002");
    ((PasswordField)view.scene().lookup("#login-password")).setText("test-only-password");
  }

  @Test void backgroundLookupDoesNotBlockFxAndLateResultCannotReplaceManualAddress() throws Exception {
    CountDownLatch began = new CountDownLatch(1), release = new CountDownLatch(1);
    LoginView view = fx(() -> {
      var v = new LoginView("", "10.0.0.1", "http://10.0.0.1:8080", c -> fail(), c -> fail());
      show(v);
      v.configureDiscovery((settings, id) -> {
        began.countDown();
        try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return found("10.0.0.2");
      }, "");
      return v;
    });
    assertTrue(began.await(2, TimeUnit.SECONDS));
    try {
      fx(() -> { sip(view).setText("10.0.0.99"); return null; });
      release.countDown(); Thread.sleep(100);
      assertEquals("10.0.0.99", fx(() -> sip(view).getText()));
      assertFalse(fx(view::automaticDiscoverySelected));
    } finally { release.countDown(); fx(() -> { close(view); return null; }); }
  }

  @Test void loginRechecksServerAndSubmitsNewSipAndHttpTogether() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<LoginView.Credentials> submitted = new AtomicReference<>();
    LoginView view = fx(() -> {
      var v = new LoginView("", "10.0.0.1", "http://10.0.0.1:8080", submitted::set, c -> fail());
      show(v);
      v.configureDiscovery((settings, id) -> found(attempts.incrementAndGet() == 1 ? "10.0.0.2" : "10.0.0.3"), "");
      return v;
    });
    try {
      waitFor(() -> sip(view).getText().equals("10.0.0.2"));
      fx(() -> { fill(view); submit(view).fire(); return null; });
      waitFor(() -> submitted.get() != null);
      assertEquals("10.0.0.3", submitted.get().sip());
      assertEquals("http://10.0.0.3:8080", submitted.get().http());
      assertEquals("test-only-password", submitted.get().password());
    } finally { fx(() -> { close(view); return null; }); }
  }

  @Test void notFoundKeepsFieldsAndAllowsExplicitManualLoginAndReSearch() throws Exception {
    AtomicReference<LoginView.Credentials> submitted = new AtomicReference<>();
    LoginView view = fx(() -> {
      var v = new LoginView("", "10.0.0.1", "http://10.0.0.1:8080", submitted::set, c -> fail());
      show(v);
      v.configureDiscovery((settings, id) -> new ServerDiscovery.Result(null, "未发现服务器"), ""); return v;
    });
    try {
      waitFor(() -> !((Button)control(view,"#login-search-server")).isDisabled());
      fx(() -> { fill(view); submit(view).fire(); return null; });
      waitFor(() -> !submit(view).isDisabled());
      assertNull(submitted.get());
      fx(() -> { ((CheckBox)control(view,"#login-auto-discovery")).setSelected(false); submit(view).fire(); return null; });
      assertEquals("10.0.0.1", submitted.get().sip());
    } finally { fx(() -> { close(view); return null; }); }
  }

  @Test void closingLoginDiscardsPendingDiscoveryCallback() throws Exception {
    CountDownLatch began = new CountDownLatch(1), release = new CountDownLatch(1);
    LoginView view = fx(() -> {
      var v = new LoginView("", "10.0.0.1", "http://10.0.0.1:8080", c -> fail(), c -> fail());
      show(v);
      v.configureDiscovery((settings, id) -> {
        began.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return found("10.0.0.2");
      }, "");return v;
    });
    assertTrue(began.await(2, TimeUnit.SECONDS));
    fx(() -> { close(view); return null; });
    release.countDown();Thread.sleep(100);
    assertEquals("10.0.0.1", fx(() -> sip(view).getText()));
  }

  @Test void freshBlankConnectionCanResolveOnFirstLoginAndSearchRestoresAutomaticMode() throws Exception {
    AtomicInteger lookupCount = new AtomicInteger();
    AtomicReference<LoginView.Credentials> submitted = new AtomicReference<>();
    LoginView view = fx(() -> {
      var v = new LoginView("", "", "", submitted::set, c -> fail()); show(v);
      v.configureDiscovery((settings, id) -> lookupCount.incrementAndGet() == 1
          ? new ServerDiscovery.Result(null,"未发现服务器") : found("10.0.0.4"), ""); return v;
    });
    try {
      waitFor(() -> !((Button)control(view,"#login-search-server")).isDisabled());
      fx(() -> { fill(view); submit(view).fire(); return null; });
      waitFor(() -> submitted.get() != null);
      assertEquals("10.0.0.4", submitted.get().sip());
      fx(() -> {
        view.error("测试恢复");sip(view).setText("10.0.0.99");
        assertFalse(view.automaticDiscoverySelected());
        ((Button)control(view,"#login-search-server")).fire();return null;
      });
      waitFor(() -> sip(view).getText().equals("10.0.0.4"));
      assertTrue(fx(view::automaticDiscoverySelected));
    } finally { fx(() -> { close(view); return null; }); }
  }

  private static ServerDiscovery.Result found(String ip) {
    return new ServerDiscovery.Result(new ServerDiscovery.Server("8f829193-256b-4a53-b264-4b50adb3101f", ip, "http://" + ip + ":8080"), "已找到服务器");
  }
}
