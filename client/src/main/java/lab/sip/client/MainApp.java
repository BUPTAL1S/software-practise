package lab.sip.client;

import java.util.Map;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public final class MainApp extends Application {
  private final SipService sip =
      new SipService(
          new SipService.Events() {
            @Override
            public void log(String msg) {
              Platform.runLater(() -> logs.appendApp(msg));
            }

            @Override
            public void log(String category, String msg) {
              Platform.runLater(() -> logs.append(category, msg));
            }

            @Override
            public void sip(boolean outgoing, String summary, String raw) {
              Platform.runLater(() -> logs.appendSip(outgoing, summary, raw));
            }

            @Override
            public void stateChanged() {
              Platform.runLater(
                  () -> {
                    refreshButtons();
                    if (sip.isRegistered() && "tcp".equalsIgnoreCase(transport.getValue())) {
                      LocalPrefs.save(server.getText(), "tcp");
                    }
                  });
            }
          });

  private LogView logs;
  private Label callStatus;
  private TextField server;
  private ComboBox<String> transport;
  private Button startEngine;
  private Button register;
  private Button callAudio;
  private Button callVideo;
  private Button previewBtn;
  private Button answerAudio;
  private Button answerVideo;
  private Button hangup;
  private HBox incomingBox;
  private Label incomingNotice;
  private String campusIp = "";

  @Override
  public void start(Stage stage) {
    Map<String, String> named = getParameters().getNamed();
    LocalPrefs.load();
    campusIp = CampusLan.detect();

    server = new TextField();
    if (!LocalPrefs.server.isEmpty()) {
      server.setText(LocalPrefs.server);
    } else if (!campusIp.isEmpty()) {
      server.setText(campusIp);
    }
    TextField domain = new TextField(named.getOrDefault("domain", "lab"));
    TextField user = new TextField(named.getOrDefault("user", "1001"));
    PasswordField password = new PasswordField();
    password.setText("1");
    TextField sipPort = new TextField(named.getOrDefault("port", "5060"));
    TextField peer = new TextField(named.getOrDefault("peer", "1002"));
    transport = new ComboBox<>();
    transport.getItems().addAll("tcp", "udp");
    transport.setValue("tcp");
    if ("udp".equalsIgnoreCase(named.getOrDefault("transport", ""))) {
      transport.setValue("udp");
    }
    if (named.containsKey("server")) {
      server.setText(named.get("server"));
    }

    Button fillLan = new Button("填入本机 IP");
    startEngine = new Button("启动引擎");
    register = new Button("注册");
    callAudio = new Button("语音呼叫");
    callVideo = new Button("视频呼叫");
    callVideo.setStyle("-fx-background-color: #1f6feb; -fx-text-fill: white; -fx-font-weight: bold;");
    previewBtn = new Button("摄像头预览");
    answerVideo = new Button("接听(视频)");
    answerVideo.setStyle("-fx-background-color: #1a7f37; -fx-text-fill: white; -fx-font-weight: bold;");
    answerAudio = new Button("接听(语音)");
    hangup = new Button("挂断");
    hangup.setStyle("-fx-background-color: #cf222e; -fx-text-fill: white;");

    logs = new LogView();
    callStatus = new Label();
    callStatus.setStyle(
        "-fx-font-size: 13px; -fx-padding: 6 8 6 8; -fx-background-color: #f6f8fa;"
            + " -fx-background-radius: 6; -fx-border-color: #d0d7de; -fx-border-radius: 6;");
    callStatus.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(callStatus, Priority.ALWAYS);

    fillLan.setOnAction(
        e -> {
          if (campusIp.isEmpty()) {
            logs.appendApp("没有探测到校园网 IPv4（跳过了回环/虚拟网卡）。");
            return;
          }
          server.setText(campusIp);
          if (!CampusLan.probeTcp(campusIp) && CampusLan.probeTcp("127.0.0.1")) {
            logs.appendApp(
                "本机访问 "
                    + campusIp
                    + ":5060 不通（Docker Desktop 常见）。同机注册请改成 127.0.0.1；Mac 仍填 "
                    + campusIp
                    + "。");
          }
        });

    startEngine.setOnAction(
        e ->
            run(
                () -> {
                  int bound =
                      sip.start(Integer.parseInt(sipPort.getText().trim()), campusIp);
                  sipPort.setText(Integer.toString(bound));
                  if (sip.hasVideoSupport()) {
                    logs.appendApp("视频支持已开启：检测到硬件视频设备及 H.264 编解码器。");
                  }
                }));

    register.setOnAction(
        e ->
            run(
                () ->
                    sip.register(
                        server.getText(),
                        user.getText().trim(),
                        password.getText(),
                        domain.getText().trim(),
                        transport.getValue())));

    callAudio.setOnAction(
        e -> run(() -> sip.call(peer.getText().trim(), domain.getText().trim(), false)));
    callVideo.setOnAction(
        e -> run(() -> sip.call(peer.getText().trim(), domain.getText().trim(), true)));

    answerAudio.setOnAction(e -> run(() -> sip.answer(false)));
    answerVideo.setOnAction(e -> run(() -> sip.answer(true)));
    hangup.setOnAction(e -> run(sip::hangup));

    previewBtn.setOnAction(
        e ->
            run(
                () -> {
                  if (sip.isPreviewActive()) {
                    sip.stopPreview();
                  } else {
                    sip.startPreview();
                  }
                }));

    // 呼叫与拨号面板
    HBox dialRow = new HBox(8);
    dialRow.setAlignment(Pos.CENTER_LEFT);
    Label peerLabel = new Label("呼叫对方:");
    peerLabel.setStyle("-fx-font-weight: bold;");

    Button chip1001 = new Button("1001");
    chip1001.setOnAction(e -> peer.setText("1001"));
    Button chip1002 = new Button("1002");
    chip1002.setOnAction(e -> peer.setText("1002"));
    Button chip1003 = new Button("1003");
    chip1003.setOnAction(e -> peer.setText("1003"));
    HBox quickPeers = new HBox(4, new Label("快捷:"), chip1001, chip1002, chip1003);
    quickPeers.setAlignment(Pos.CENTER_LEFT);

    HBox callActions = new HBox(8, callVideo, callAudio, hangup);
    callActions.setAlignment(Pos.CENTER_LEFT);

    dialRow.getChildren().addAll(peerLabel, peer, quickPeers, callActions);
    HBox.setHgrow(peer, Priority.NEVER);

    // 来电提示横幅
    incomingNotice = new Label("收到来电");
    incomingNotice.setStyle("-fx-font-weight: bold; -fx-text-fill: #1a7f37;");
    incomingBox = new HBox(10, incomingNotice, answerVideo, answerAudio);
    incomingBox.setAlignment(Pos.CENTER_LEFT);
    incomingBox.setPadding(new Insets(6, 10, 6, 10));
    incomingBox.setStyle(
        "-fx-background-color: #dafbe1; -fx-background-radius: 6; -fx-border-color: #4ac26b; -fx-border-radius: 6;");
    incomingBox.setVisible(false);
    incomingBox.setManaged(false);

    VBox callCard = new VBox(8, dialRow, incomingBox);
    callCard.setPadding(new Insets(10));
    callCard.setStyle(
        "-fx-background-color: #ffffff; -fx-border-color: #d0d7de; -fx-border-radius: 8; -fx-background-radius: 8;");

    // 配置折叠栏
    GridPane cfgGrid = new GridPane();
    cfgGrid.setHgap(8);
    cfgGrid.setVgap(8);
    cfgGrid.setPadding(new Insets(8));
    int r = 0;
    cfgGrid.add(new Label("SIP 服务器"), 0, r);
    HBox serverRow = new HBox(8, server, fillLan);
    HBox.setHgrow(server, Priority.ALWAYS);
    cfgGrid.add(serverRow, 1, r++);
    cfgGrid.add(new Label("传输"), 0, r);
    cfgGrid.add(transport, 1, r++);
    cfgGrid.add(new Label("域"), 0, r);
    cfgGrid.add(domain, 1, r++);
    cfgGrid.add(new Label("用户"), 0, r);
    cfgGrid.add(user, 1, r++);
    cfgGrid.add(new Label("密码"), 0, r);
    cfgGrid.add(password, 1, r++);
    cfgGrid.add(new Label("本机 SIP 端口"), 0, r);
    cfgGrid.add(sipPort, 1, r++);
    HBox cfgButtons = new HBox(8, startEngine, register);
    cfgGrid.add(cfgButtons, 0, r++, 2, 1);
    GridPane.setHgrow(serverRow, Priority.ALWAYS);

    TitledPane cfgPane = new TitledPane("网络与账号设置 (Kamailio / 本机端口)", cfgGrid);
    cfgPane.setExpanded(true);

    // 顶部状态栏
    HBox topStatus = new HBox(8, callStatus, previewBtn);
    topStatus.setAlignment(Pos.CENTER_LEFT);

    VBox root = new VBox(10, topStatus, callCard, cfgPane, logs);
    root.setPadding(new Insets(12));
    VBox.setVgrow(logs, Priority.ALWAYS);

    stage.setTitle("SIP 客户端 · 阶段 2 音视频");
    stage.setScene(new Scene(root, 780, 680));
    stage.setOnCloseRequest(e -> sip.shutdown());
    stage.show();

    logs.appendApp("SIP 服务器 = Kamailio 所在机器的校园网 IP；本机 IP 只用于 Contact。");
    logs.appendApp(
        campusIp.isEmpty()
            ? "未探测到校园网 IPv4。"
            : "本机校园网：" + campusIp);
    String filled = server.getText() == null ? "" : server.getText().trim();
    if (!named.containsKey("server")
        && !CampusLan.probeTcp(filled.isEmpty() ? campusIp : filled)
        && CampusLan.probeTcp("127.0.0.1")) {
      server.setText("127.0.0.1");
      logs.appendApp(
          "本机连校园网 IP:5060 不通（Docker Desktop 连自己的局域网地址会超时），"
              + "SIP 服务器已改为 127.0.0.1。Mac 端仍填 "
              + campusIp
              + "。");
    } else if (!LocalPrefs.server.isEmpty()) {
      if (CampusLan.probeTcp(LocalPrefs.server)) {
        logs.appendApp("上次代理 " + LocalPrefs.server + " TCP 可达。");
      } else {
        logs.appendApp(
            "上次代理不可达，可点「填入本机 IP」或问信令要新 IP（当前仍填着 "
                + LocalPrefs.server
                + "）。");
      }
    }
    logs.appendApp("同机第二窗口：mvn javafx:run -Djavafx.args=\"--user=1002 --port=5062 --peer=1001\"");
    logs.appendApp("必须用 TCP。支持视频呼叫与仅音频呼叫；视频将在呼叫接通后自动由 PJSUA2 渲染窗口呈现。");
    logs.appendApp("两窗口不要用同一个账号，Kamailio 内存 usrloc 会被后注册的顶掉。");
    refreshButtons();
  }

  private void refreshButtons() {
    boolean on = sip.isStarted();
    boolean reg = sip.isRegistered();
    boolean incoming = sip.hasIncoming();
    boolean videoSupported = sip.hasVideoSupport();

    startEngine.setDisable(on);
    register.setDisable(!on);
    previewBtn.setDisable(!on || !videoSupported);
    previewBtn.setText(sip.isPreviewActive() ? "关闭预览" : "摄像头预览");

    callAudio.setDisable(!reg);
    callVideo.setDisable(!reg || !videoSupported);

    answerAudio.setDisable(!incoming);
    answerVideo.setDisable(!incoming || !videoSupported);
    incomingBox.setVisible(incoming);
    incomingBox.setManaged(incoming);
    if (incoming) {
      incomingNotice.setText("来电响铃中：可选择接听视频或仅接听语音");
    }

    hangup.setDisable(!on);
    callStatus.setText("状态  " + sip.statusText());
  }

  private void run(Throwing action) {
    try {
      action.run();
    } catch (Throwable ex) {
      logs.append("错误", ex.getClass().getSimpleName() + "：" + ex.getMessage());
    }
    refreshButtons();
  }

  @FunctionalInterface
  private interface Throwing {
    void run() throws Exception;
  }

  public static void main(String[] args) {
    launch(args);
  }
}
