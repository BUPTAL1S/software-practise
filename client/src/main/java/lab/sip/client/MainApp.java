package lab.sip.client;

import java.util.Map;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
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
  private Button call;
  private Button answer;
  private Button hangup;
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
    call = new Button("呼叫");
    answer = new Button("接听");
    hangup = new Button("挂断");
    logs = new LogView();
    callStatus = new Label();
    callStatus.setStyle(
        "-fx-font-size: 13px; -fx-padding: 6 8 6 8; -fx-background-color: #f6f8fa;"
            + " -fx-background-radius: 6; -fx-border-color: #d0d7de; -fx-border-radius: 6;");
    callStatus.setMaxWidth(Double.MAX_VALUE);

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
    call.setOnAction(
        e -> run(() -> sip.call(peer.getText().trim(), domain.getText().trim())));
    answer.setOnAction(e -> run(sip::answer));
    hangup.setOnAction(e -> run(sip::hangup));

    GridPane grid = new GridPane();
    grid.setHgap(8);
    grid.setVgap(8);
    int r = 0;
    grid.add(new Label("SIP 服务器"), 0, r);
    HBox serverRow = new HBox(8, server, fillLan);
    HBox.setHgrow(server, Priority.ALWAYS);
    grid.add(serverRow, 1, r++);
    grid.add(new Label("传输"), 0, r);
    grid.add(transport, 1, r++);
    grid.add(new Label("域"), 0, r);
    grid.add(domain, 1, r++);
    grid.add(new Label("用户"), 0, r);
    grid.add(user, 1, r++);
    grid.add(new Label("密码"), 0, r);
    grid.add(password, 1, r++);
    grid.add(new Label("本机 SIP 端口"), 0, r);
    grid.add(sipPort, 1, r++);
    grid.add(new Label("对方用户"), 0, r);
    grid.add(peer, 1, r++);
    HBox buttons = new HBox(8, startEngine, register, call, answer, hangup);
    grid.add(buttons, 0, r++, 2, 1);
    GridPane.setHgrow(serverRow, Priority.ALWAYS);

    VBox root = new VBox(10, grid, callStatus, logs);
    root.setPadding(new Insets(12));
    VBox.setVgrow(logs, Priority.ALWAYS);

    stage.setTitle("SIP 客户端 · 阶段 1 音频");
    stage.setScene(new Scene(root, 760, 640));
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
    logs.appendApp("必须用 TCP。只让一边呼叫，等对端出现「来电」再接听。");
    logs.appendApp("两窗口不要用同一个账号，Kamailio 内存 usrloc 会被后注册的顶掉。");
    refreshButtons();
  }

  private void refreshButtons() {
    boolean on = sip.isStarted();
    startEngine.setDisable(on);
    register.setDisable(!on);
    call.setDisable(!sip.isRegistered());
    answer.setDisable(!sip.hasIncoming());
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
