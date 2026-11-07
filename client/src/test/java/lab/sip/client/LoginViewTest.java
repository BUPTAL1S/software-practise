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
        LoginView login = new LoginView("", "10.21.233.158", "http://10.21.233.158:8080", submitted::set,
            credentials -> fail("Login must not submit a registration"));
        stage.setScene(login.scene()); stage.show();
        login.scene().getRoot().applyCss(); login.scene().getRoot().layout();
        Button button = (Button) login.scene().lookup("#login-submit");
        var bounds = button.localToScene(button.getBoundsInLocal());
        assertTrue(bounds.getMaxY() <= login.scene().getHeight(), "Login action must fit the window");
        snapshot(login.scene(), "target/login-preview.png");
        button.fire(); assertNull(submitted.get(), "Empty password must not submit");
        var account = (javafx.scene.control.TextField) login.scene().lookup("#login-account");
        assertEquals("", account.getText());
        account.setText("1002");
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

  @Test void registrationChecksFourDigitsAndConfirmationBeforeSubmitting() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.runLater(() -> {
      Stage stage = new Stage();
      try {
        AtomicReference<LoginView.Credentials> registered = new AtomicReference<>();
        LoginView login = new LoginView("", "127.0.0.1", "http://localhost:8080",
            credentials -> fail("Registration must not submit a login"), registered::set);
        stage.setScene(login.scene()); stage.show();
        ((javafx.scene.control.Hyperlink) login.scene().lookup("#login-register")).fire();
        login.scene().getRoot().applyCss(); login.scene().getRoot().layout();
        snapshot(login.scene(), "target/registration-preview.png");
        var account = (javafx.scene.control.TextField) login.scene().lookup("#login-account");
        var password = (PasswordField) login.scene().lookup("#login-password");
        var confirmation = (PasswordField) login.scene().lookup("#register-confirm-password");
        Button submit = (Button) login.scene().lookup("#login-submit");
        String chosenPassword = " custom-密码\\\" ";
        password.setText(chosenPassword);
        confirmation.setText(chosenPassword);
        for (String invalid : new String[] {"123", "12345", "abcd", "１２３４"}) {
          account.setText(invalid); submit.fire();
          assertNull(registered.get(), "Invalid account must not reach the backend");
        }
        account.setText("0123"); confirmation.setText("different"); submit.fire();
        assertNull(registered.get());
        password.setText("123"); confirmation.setText("123"); submit.fire();
        assertNull(registered.get(), "A short password must not reach the backend");
        password.setText(chosenPassword);
        confirmation.setText(chosenPassword);
        login.scene().getRoot().applyCss(); login.scene().getRoot().layout();
        assertTrue(submit.localToScene(submit.getBoundsInLocal()).getMaxY() <= login.scene().getHeight(),
            "Registration action must fit the window");
        submit.fire();
        assertEquals("0123", registered.get().username());
        assertEquals(chosenPassword, registered.get().password());
        assertTrue(submit.isDisabled());
        login.clearPassword();
        assertEquals("", confirmation.getText());
        login.error("账号已存在");
        assertFalse(submit.isDisabled());
        ((javafx.scene.control.Hyperlink) login.scene().lookup("#login-register")).fire();
        assertFalse(confirmation.getParent().isManaged());
        assertEquals("", password.getText());
      } catch (Throwable error) { failure.set(error); }
      finally { stage.close(); done.countDown(); }
    });
    assertTrue(done.await(10, TimeUnit.SECONDS));
    if (failure.get() != null) throw new AssertionError(failure.get());
  }

  private static void snapshot(javafx.scene.Scene scene, String path) throws java.io.IOException {
    var snapshot = scene.snapshot(null);
    java.awt.image.BufferedImage image = new java.awt.image.BufferedImage((int) snapshot.getWidth(),
        (int) snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
      image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
    javax.imageio.ImageIO.write(image, "png", new java.io.File(path));
  }
}
