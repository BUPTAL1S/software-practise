package lab.sip.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

public final class MainApp extends Application {
  private final SipService sip = new SipService(msg -> Platform.runLater(() -> append(msg)));
  private TextArea log;

  @Override
  public void start(Stage stage) {
    TextField server = new TextField("127.0.0.1");
    TextField domain = new TextField("lab");
    TextField user = new TextField("alice");
    PasswordField password = new PasswordField();
    TextField sipPort = new TextField("5060");
    TextField peer = new TextField("bob");

    Button startEngine = new Button("启动引擎");
    Button register = new Button("注册");
    Button call = new Button("呼叫");
    Button answer = new Button("接听");
    Button hangup = new Button("挂断");
    log = new TextArea();
    log.setEditable(false);
    log.setPrefRowCount(12);

    startEngine.setOnAction(
        e -> run(
            () -> sip.start(Integer.parseInt(sipPort.getText().trim()))));
    register.setOnAction(
        e ->
            run(
                () ->
                    sip.register(
                        server.getText(),
                        user.getText().trim(),
                        password.getText(),
                        domain.getText().trim())));
    call.setOnAction(
        e -> run(() -> sip.call(peer.getText(), domain.getText().trim())));
    answer.setOnAction(e -> run(sip::answer));
    hangup.setOnAction(e -> run(sip::hangup));

    GridPane grid = new GridPane();
    grid.setHgap(8);
    grid.setVgap(8);
    grid.setPadding(new Insets(12));
    int r = 0;
    grid.add(new Label("SIP 服务器"), 0, r);
    grid.add(server, 1, r++);
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

    stage.setTitle("SIP 客户端 · 阶段 1 音频");
    stage.setScene(new Scene(grid, 560, 420));
    stage.setOnCloseRequest(e -> sip.shutdown());
    stage.show();
    append("先启动引擎再注册。同机开两个窗口时端口改成 5060 和 5062。");
  }

  private void run(Throwing action) {
    try {
      action.run();
    } catch (Exception ex) {
      append("错误：" + ex.getMessage());
    }
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
