package lab.sip.client;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

class LoginViewTest {
  @Test void accountPasswordFormRemainsReachableInSmallWindow() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.runLater(() -> {
      Stage owner = new Stage();
      Stage dialog = null;
      try {
        AccountSettingsWindow.show(owner, new HttpFileClient("http://127.0.0.1:8088"), "1002", () -> {}, value -> {}, () -> {});
        dialog = (Stage) javafx.stage.Window.getWindows().stream().filter(window ->
            window instanceof Stage candidate && candidate.getTitle().equals("我的账号")).findFirst().orElseThrow();
        var tabs = (javafx.scene.control.TabPane) dialog.getScene().lookup(".tab-pane");
        tabs.getSelectionModel().select(1);
        dialog.setHeight(590);
        dialog.getScene().getRoot().applyCss(); dialog.getScene().getRoot().layout();
        var scroll = (javafx.scene.control.ScrollPane) tabs.getTabs().get(1).getContent();
        assertTrue(scroll.isFitToWidth());
        assertTrue(scroll.getViewportBounds().getHeight() > 0);
        scroll.setVvalue(1);
        assertFalse(scroll.isDisabled());
      } catch (Throwable error) { failure.set(error); }
      finally { if (dialog != null) dialog.close(); owner.close(); done.countDown(); }
    });
    assertTrue(done.await(10, TimeUnit.SECONDS));
    if (failure.get() != null) throw new AssertionError(failure.get());
  }

  @Test void renderAndSubmitConfiguredLoginWithoutNetworkRequest() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.runLater(() -> {
      Stage stage = new Stage();
      try {
        AtomicReference<LoginView.Credentials> submitted = new AtomicReference<>();
        LoginView login = new LoginView("1002", "10.21.233.158", "http://10.21.233.158:8080", submitted::set);
        stage.setScene(login.scene()); stage.show();
        login.scene().getRoot().applyCss(); login.scene().getRoot().layout();
        Button button = (Button) login.scene().lookup("#login-submit");
        var bounds = button.localToScene(button.getBoundsInLocal());
        assertTrue(bounds.getMaxY() <= login.scene().getHeight(), "Login action must fit the window");
        var snapshot = login.scene().snapshot(null);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage((int) snapshot.getWidth(),
            (int) snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
          image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
        javax.imageio.ImageIO.write(image, "png", new java.io.File("target/login-preview.png"));
        button.fire(); assertNull(submitted.get(), "Empty password must not submit");
        ((PasswordField) login.scene().lookup("#login-password")).setText("test-only");
        button.fire();
        assertEquals("1002", submitted.get().username());
        assertEquals("10.21.233.158", submitted.get().sip());
        login.error("测试错误提示"); assertFalse(button.isDisabled());
      } catch (Throwable error) { failure.set(error); }
      finally { stage.close(); done.countDown(); }
    });
    assertTrue(done.await(10, TimeUnit.SECONDS));
    if (failure.get() != null) throw new AssertionError(failure.get());
  }
}
