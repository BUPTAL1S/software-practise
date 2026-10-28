package lab.sip.client;

import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;

/** Account-first login; network details are available only when needed. */
final class LoginView {
  record Credentials(String username, String password, String sip, String http) {}
  private final TextField account;
  private final PasswordField password = new PasswordField();
  private final TextField sip;
  private final TextField http;
  private final Label feedback = new Label();
  private final Button signIn = new Button("登录并上线");
  private final VBox form;
  private final Scene scene;

  LoginView(String username, String sipHost, String httpUrl, Consumer<Credentials> authenticate) {
    account = new TextField(username);
    account.setId("login-account");
    password.setId("login-password");
    signIn.setId("login-submit");
    account.setPromptText("输入账号，如 1002");
    password.setPromptText("输入登录密码");
    sip = new TextField(sipHost);
    http = new TextField(httpUrl);
    TextField visiblePassword = new TextField();
    visiblePassword.textProperty().bindBidirectional(password.textProperty());
    visiblePassword.setPromptText("输入登录密码");
    visiblePassword.setVisible(false);
    visiblePassword.setManaged(false);
    CheckBox reveal = new CheckBox("显示密码");
    reveal.selectedProperty().addListener((obs, before, selected) -> {
      password.setVisible(!selected); password.setManaged(!selected);
      visiblePassword.setVisible(selected); visiblePassword.setManaged(selected);
    });
    StackPane passwordBox = new StackPane(password, visiblePassword);
    VBox connections = new VBox(8, label("通话服务器", "login-label"), sip,
        label("账号与消息服务器", "login-label"), http);
    connections.setPadding(new Insets(12, 0, 4, 0));
    TitledPane advanced = new TitledPane("连接设置 · 已自动配置", connections);
    advanced.setExpanded(false);
    feedback.getStyleClass().add("login-feedback");
    feedback.setWrapText(true);
    feedback.setMinHeight(40);
    signIn.setDefaultButton(true);
    signIn.setMaxWidth(Double.MAX_VALUE);
    signIn.getStyleClass().add("login-primary");
    signIn.setOnAction(e -> {
      if (account.getText().isBlank() || password.getText().isEmpty()) {
        error("请填写账号和密码"); return;
      }
      if (sip.getText().isBlank() || http.getText().isBlank()) {
        error("请检查连接设置中的服务器地址"); return;
      }
      setBusy(true);
      authenticate.accept(new Credentials(account.getText().trim(), password.getText(),
          sip.getText().trim(), http.getText().trim()));
    });
    form = new VBox(12, label("欢迎回来", "login-title"), label("登录账号，开始聊天和通话", "login-muted"),
        label("账号", "login-label"), account, label("密码", "login-label"), passwordBox, reveal,
        advanced, feedback, signIn);
    form.setMaxWidth(350);
    form.setAlignment(Pos.CENTER_LEFT);
    SVGPath bubble = new SVGPath();
    bubble.setContent("M8 2h48a6 6 0 0 1 6 6v32a6 6 0 0 1-6 6H24L8 58V46a6 6 0 0 1-6-6V8a6 6 0 0 1 6-6z M17 19h30 M17 29h20");
    bubble.setStyle("-fx-fill: transparent; -fx-stroke: #99e2bb; -fx-stroke-width: 3;");
    VBox brand = new VBox(28, bubble, label("SIP Connect", "login-brand-title"),
        label("消息、语音与视频\n让协作自然发生", "login-brand-copy"));
    brand.setAlignment(Pos.CENTER_LEFT);
    brand.setPadding(new Insets(48));
    brand.getStyleClass().add("login-brand");
    brand.setPrefWidth(345);
    brand.setMinWidth(280);
    StackPane right = new StackPane(form);
    right.setPadding(new Insets(34, 40, 34, 40));
    HBox.setHgrow(right, Priority.ALWAYS);
    HBox root = new HBox(brand, right);
    root.getStyleClass().add("login-root");
    scene = new Scene(root, 860, 660);
    scene.getStylesheets().add(LoginView.class.getResource("/account-theme.css").toExternalForm());
  }

  Scene scene() { return scene; }
  void setBusy(boolean busy) {
    form.setDisable(busy);
    signIn.setText(busy ? "正在连接…" : "登录并上线");
    feedback.setText(busy ? "正在验证账号并连接通话服务…" : "");
  }
  void error(String message) { setBusy(false); feedback.setText(message); }
  void clearPassword() { password.clear(); }
  static Label label(String text, String style) {
    Label label = new Label(text); label.getStyleClass().add(style); label.setWrapText(true); return label;
  }
}
