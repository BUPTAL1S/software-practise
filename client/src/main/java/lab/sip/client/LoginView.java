package lab.sip.client;

import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import javafx.application.Platform;
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
  private final PasswordField confirmation = new PasswordField();
  private final TextField sip;
  private final TextField http;
  private final Label feedback = new Label();
  private final Button signIn = new Button("登录并上线");
  private final VBox form;
  private final Scene scene;
  private boolean registering;
  private final TitledPane advanced;
  private final CheckBox automatic = new CheckBox("自动发现服务器");
  private final Button search = new Button("重新搜索");
  private ServerDiscovery.Lookup discovery;
  private String serverId = "";
  private long lookupGeneration;
  private boolean applyingConnection, closed, busy;

  LoginView(String username, String sipHost, String httpUrl, Consumer<Credentials> authenticate,
      Consumer<Credentials> createAccount) {
    account = new TextField(username);
    account.setId("login-account");
    password.setId("login-password");
    signIn.setId("login-submit");
    account.setPromptText("输入登录账号");
    password.setPromptText("输入登录密码");
    sip = new TextField(sipHost);
    http = new TextField(httpUrl);
    sip.setId("login-sip-server"); http.setId("login-http-server");
    automatic.setSelected(true); automatic.setId("login-auto-discovery");
    search.setId("login-search-server");
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
    confirmation.setId("register-confirm-password");
    confirmation.setPromptText("再次输入密码");
    VBox confirmBox = new VBox(8, label("确认密码", "login-label"), confirmation);
    confirmBox.setVisible(false);
    confirmBox.setManaged(false);
    VBox connections = new VBox(8, label("通话服务器", "login-label"), sip,
        label("账号与消息服务器", "login-label"), http, new HBox(12, automatic, search));
    connections.setPadding(new Insets(12, 0, 4, 0));
    advanced = new TitledPane("连接设置", connections);
    advanced.setId("login-connections");
    advanced.setExpanded(false);
    sip.textProperty().addListener((obs, old, next) -> connectionEdited());
    http.textProperty().addListener((obs, old, next) -> connectionEdited());
    automatic.selectedProperty().addListener((obs, old, selected) -> {
      if (applyingConnection) return;
      lookupGeneration++;
      search.setDisable(false);
      if (selected) refreshDiscovery();
      else advanced.setText("连接设置 · 手动设置");
    });
    search.setOnAction(event -> {
      applyingConnection = true; automatic.setSelected(true); applyingConnection = false;
      refreshDiscovery();
    });
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
      if (registering) {
        if (!account.getText().trim().matches("[0-9]{4}")) {
          error("注册账号必须为四位数字"); return;
        }
        String chosenPassword = password.getText();
        if (chosenPassword.isBlank() || chosenPassword.codePointCount(0, chosenPassword.length()) < 4
            || chosenPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
          error("密码至少 4 个字符，UTF-8 长度不超过 72 字节"); return;
        }
        if (!password.getText().equals(confirmation.getText())) {
          error("两次输入的密码不一致"); return;
        }
      }
      if ((discovery == null || !automatic.isSelected()) && (sip.getText().isBlank() || http.getText().isBlank())) {
        error("请检查连接设置中的服务器地址"); return;
      }
      Consumer<Credentials> submit = registering ? createAccount : authenticate;
      Credentials entered = new Credentials(account.getText().trim(), password.getText(),
          sip.getText().trim(), http.getText().trim());
      setBusy(true);
      if (discovery != null && automatic.isSelected()) lookup(entered, submit);
      else submit.accept(entered);
    });
    Label title = label("欢迎回来", "login-title");
    Label hint = label("登录账号，开始聊天和通话", "login-muted");
    Hyperlink switchMode = new Hyperlink("注册新账号");
    switchMode.setId("login-register");
    switchMode.setOnAction(e -> {
      registering = !registering;
      clearPassword();
      feedback.setText("");
      confirmBox.setVisible(registering);
      confirmBox.setManaged(registering);
      account.setPromptText(registering ? "输入四位数字账号" : "输入登录账号");
      title.setText(registering ? "注册新账号" : "欢迎回来");
      hint.setText(registering ? "设置四位数字账号和密码，注册后直接上线" : "登录账号，开始聊天和通话");
      signIn.setText(registering ? "注册并上线" : "登录并上线");
      switchMode.setText(registering ? "已有账号，返回登录" : "注册新账号");
    });
    form = new VBox(10, title, hint,
        label("账号", "login-label"), account, label("密码", "login-label"), passwordBox, reveal,
        confirmBox, advanced, feedback, signIn, switchMode);
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
    form.setMinHeight(Region.USE_PREF_SIZE);
    ScrollPane scroll = new ScrollPane(form);
    scroll.setFitToWidth(true); scroll.setFitToHeight(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");
    scroll.setMaxWidth(365);
    StackPane right = new StackPane(scroll);
    right.setPadding(new Insets(34, 40, 34, 40));
    HBox.setHgrow(right, Priority.ALWAYS);
    HBox root = new HBox(brand, right);
    root.getStyleClass().add("login-root");
    scene = new Scene(root, 860, 660);
    scene.getStylesheets().add(LoginView.class.getResource("/account-theme.css").toExternalForm());
  }

  Scene scene() { return scene; }
  String discoveredServerId() { return serverId; }
  boolean automaticDiscoverySelected() { return automatic.isSelected(); }
  void configureDiscovery(ServerDiscovery.Lookup discovery, String rememberedId) {
    configureDiscovery(discovery, rememberedId, true);
  }
  void configureDiscovery(ServerDiscovery.Lookup discovery, String rememberedId, boolean enabled) {
    this.discovery = discovery;
    this.serverId = rememberedId == null ? "" : rememberedId;
    applyingConnection = true; automatic.setSelected(enabled); applyingConnection = false;
    refreshDiscovery();
  }
  void closeDiscovery() { closed = true; lookupGeneration++; }
  private void connectionEdited() {
    if (applyingConnection) return;
    lookupGeneration++; serverId = "";
    automatic.setSelected(false);
    search.setDisable(false);
    advanced.setText("连接设置 · 手动设置");
  }
  void refreshDiscovery() {
    if (closed || busy || discovery == null || !automatic.isSelected()) return;
    lookup(new Credentials("", "", sip.getText().trim(), http.getText().trim()), null);
  }
  private void lookup(Credentials entered, Consumer<Credentials> submit) {
    long generation = ++lookupGeneration;
    String rememberedId = serverId;
    advanced.setText("连接设置 · 正在搜索服务器…");
    search.setDisable(true);
    Thread worker = new Thread(() -> {
      ServerDiscovery.Result result;
      try { result = discovery.find(new ConnectionDefaults.Settings(entered.sip(), entered.http()), rememberedId); }
      catch (Exception failure) { result = new ServerDiscovery.Result(null, "搜索失败，可重新搜索或手动设置"); }
      ServerDiscovery.Result found = result;
      Platform.runLater(() -> {
        if (closed || generation != lookupGeneration) return;
        search.setDisable(false);
        advanced.setText("连接设置 · " + found.message());
        if (found.server() != null) {
          applyingConnection = true;
          sip.setText(found.server().sip()); http.setText(found.server().http());
          applyingConnection = false;
          serverId = found.server().id();
          if (submit != null) submit.accept(new Credentials(entered.username(), entered.password(),
              found.server().sip(), found.server().http()));
        } else if (submit != null) {
          error(found.message() + "。若已手动填写正确地址，请取消“自动发现服务器”后重试。");
          advanced.setExpanded(true);
        }
      });
    }, "login-server-discovery");
    worker.setDaemon(true); worker.start();
  }
  void setBusy(boolean busy) {
    this.busy = busy;
    form.setDisable(busy);
    String action = registering ? "注册并上线" : "登录并上线";
    String progress = registering ? "正在创建账号并连接通话服务…" : "正在验证账号并连接通话服务…";
    signIn.setText(busy ? "正在连接…" : action);
    feedback.setText(busy ? progress : "");
  }
  void error(String message) { setBusy(false); feedback.setText(message); }
  void clearPassword() { password.clear(); confirmation.clear(); }
  static Label label(String text, String style) {
    Label label = new Label(text); label.getStyleClass().add(style); label.setWrapText(true); return label;
  }
}
