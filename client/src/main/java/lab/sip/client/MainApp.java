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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

public final class MainApp extends Application {
  private final SipService sip =
      new SipService(
          new SipService.Events() {
            @Override
            public void log(String msg) {
              Platform.runLater(() -> append(msg));
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

  private TextArea log;
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
    log = new TextArea();
    log.setEditable(false);
    log.setPrefRowCount(12);

    fillLan.setOnAction(
        e -> {
          if (campusIp.isEmpty()) {
            append("没有探测到校园网 IPv4（跳过了回环/虚拟网卡）。");
            return;
          }
          server.setText(campusIp);
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
    grid.setPadding(new Insets(12));
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
    grid.add(log, 0, r, 2, 1);
    GridPane.setHgrow(serverRow, Priority.ALWAYS);

    stage.setTitle("SIP 客户端 · 阶段 1 音频");
    stage.setScene(new Scene(grid, 620, 480));
    stage.setOnCloseRequest(e -> sip.shutdown());
    stage.show();

    append("SIP 服务器 = Kamailio 所在机器的校园网 IP；本机 IP 只用于 Contact。");
    append(
        campusIp.isEmpty()
            ? "未探测到校园网 IPv4。"
            : "本机校园网：" + campusIp);
    if (!LocalPrefs.server.isEmpty()) {
      if (CampusLan.probeTcp(LocalPrefs.server)) {
        append("上次代理 " + LocalPrefs.server + " TCP 可达。");
      } else {
        append(
            "上次代理不可达，可点「填入本机 IP」或问信令要新 IP（当前仍填着 "
                + LocalPrefs.server
                + "）。");
      }
    }
    append("同机第二窗口：mvn javafx:run -Djavafx.args=\"--user=1002 --port=5062 --peer=1001\"");
    append("必须用 TCP。只让一边呼叫，等对端出现「来电」再接听。");
    append("两窗口不要用同一个账号，Kamailio 内存 usrloc 会被后注册的顶掉。");
    refreshButtons();
  }

  private void refreshButtons() {
    boolean on = sip.isStarted();
    startEngine.setDisable(on);
    register.setDisable(!on);
    call.setDisable(!sip.isRegistered());
    answer.setDisable(!sip.hasIncoming());
    hangup.setDisable(!on);
  }

  private void run(Throwing action) {
    try {
      action.run();
    } catch (Exception ex) {
      append("错误：" + ex.getMessage());
    }
    refreshButtons();
  }

  private void append(String msg) {
    log.appendText(msg + "\n");
  }

  @FunctionalInterface
  private interface Throwing {
    void run() throws Exception;
  }

  public static void main(String[] args) {
    launch(args);
  }
}
