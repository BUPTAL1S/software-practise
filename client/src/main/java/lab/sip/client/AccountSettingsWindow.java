package lab.sip.client;

import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

final class AccountSettingsWindow {
  static void show(Window owner, HttpFileClient client, String username,
      Runnable profileChanged, Consumer<String> passwordChanged, Runnable editAvatar) {
    Stage stage = new Stage();
    stage.initOwner(owner);
    stage.setTitle("我的账号");
    Label message = LoginView.label("", "login-feedback");
    message.setMinHeight(38);
    TextField nickname = new TextField(client.getNickname().isBlank() ? username : client.getNickname());
    Button saveName = new Button("保存昵称");
    saveName.getStyleClass().add("login-primary");
    Button avatar = new Button("更换头像");
    avatar.setOnAction(e -> { stage.close(); editAvatar.run(); });
    VBox profile = new VBox(14, LoginView.label("昵称", "login-label"), nickname,
        LoginView.label("账号 " + username + " · " + ("ADMIN".equals(client.getRole()) ? "管理员" : "普通用户"), "login-muted"),
        saveName, avatar);
    saveName.setOnAction(e -> {
      if (nickname.getText().isBlank()) { message.setText("请填写昵称"); return; }
      String next = nickname.getText().strip();
      perform(profile, message, () -> client.updateNickname(next), () -> {
        profileChanged.run(); message.setText("昵称已保存，其他成员的通讯录会自动更新");
      });
    });
    PasswordField current = new PasswordField(), next = new PasswordField(), confirm = new PasswordField();
    Button savePassword = new Button("修改密码");
    savePassword.getStyleClass().add("login-primary");
    VBox security = new VBox(12, LoginView.label("原密码", "login-label"), current,
        LoginView.label("新密码", "login-label"), next,
        LoginView.label("确认新密码", "login-label"), confirm,
        LoginView.label("新密码至少 6 个字符。修改后其他登录会话将失效。", "login-muted"), savePassword);
    savePassword.setOnAction(e -> {
      if (!next.getText().equals(confirm.getText())) { message.setText("两次输入的新密码不一致"); return; }
      String previous = current.getText(), replacement = next.getText();
      if (previous.isEmpty() || replacement.isBlank()) { message.setText("请填写原密码和新密码"); return; }
      perform(security, message, () -> client.changePassword(previous, replacement), () -> {
        passwordChanged.accept(replacement);
        current.clear(); next.clear(); confirm.clear();
        message.setText("密码已修改，下次请使用新密码登录");
      });
    });
    ScrollPane profileScroll = new ScrollPane(profile), securityScroll = new ScrollPane(security);
    for (ScrollPane scroll : new ScrollPane[]{profileScroll, securityScroll}) {
      scroll.setFitToWidth(true);
      scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
      scroll.setStyle("-fx-background-color: transparent; -fx-background: #f7faf8;");
    }
    TabPane tabs = new TabPane(new Tab("个人资料", profileScroll), new Tab("修改密码", securityScroll));
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    VBox root = new VBox(16, LoginView.label("我的账号", "login-title"), tabs, message);
    root.getStyleClass().add("account-root");
    VBox.setVgrow(tabs, javafx.scene.layout.Priority.ALWAYS);
    Scene scene = new Scene(root, 460, 700);
    scene.getStylesheets().add(AccountSettingsWindow.class.getResource("/account-theme.css").toExternalForm());
    stage.setScene(scene);
    stage.setMinWidth(430); stage.setMinHeight(590);
    stage.show();
  }

  @FunctionalInterface private interface Work { void run() throws Exception; }
  private static void perform(VBox form, Label message, Work work, Runnable completed) {
    form.setDisable(true); message.setText("正在保存…");
    Thread thread = new Thread(() -> {
      try {
        work.run();
        Platform.runLater(() -> { form.setDisable(false); completed.run(); });
      } catch (Exception error) {
        Platform.runLater(() -> { form.setDisable(false); message.setText(error.getMessage()); });
      }
    }, "account-update");
    thread.setDaemon(true); thread.start();
  }
}
