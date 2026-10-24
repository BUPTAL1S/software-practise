package lab.sip.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import java.util.function.Consumer;

/**
 * 独立通话窗口。
 *
 * <p>语音通话使用完整窗口；视频通话使用紧凑控制条，远端画面由 PJSIP 原生窗口
 * 独立显示，本地 PiP 由原生预览窗口叠加在远端窗口右下角。
 */
final class CallWindow {
  private final Window owner;
  private Stage stage;
  private Label titleLabel;
  private Label statusLabel;
  private Label timerLabel;
  private Label avatarLabel;
  private StackPane avatarPane;
  private Button muteBtn;
  private Button cameraBtn;
  private Button hangupBtn;

  private Timeline timer;
  private long startedAt;
  private boolean video;
  private boolean cameraOn;
  private boolean muted;
  private boolean connected;
  private Runnable onHangup = () -> {};
  private Consumer<Boolean> onMute = value -> {};
  private Consumer<Boolean> onCamera = value -> {};

  CallWindow(Window owner) {
    this.owner = owner;
  }

  void show(
      String target,
      boolean video,
      boolean cameraOn,
      boolean connected,
      Runnable onHangup,
      Consumer<Boolean> onMute,
      Consumer<Boolean> onCamera) {
    this.onHangup = onHangup == null ? () -> {} : onHangup;
    this.onMute = onMute == null ? value -> {} : onMute;
    this.onCamera = onCamera == null ? value -> {} : onCamera;
    this.video = video;
    this.cameraOn = cameraOn;
    this.connected = connected;
    if (stage == null) {
      build(target);
      stage.show();
    }
    update(target, video, cameraOn, connected, muted);
    if (connected) {
      startTimer();
    }
  }

  void update(String target, boolean video, boolean cameraOn, boolean connected) {
    update(target, video, cameraOn, connected, muted);
  }

  private void update(
      String target, boolean video, boolean cameraOn, boolean connected, boolean muted) {
    if (stage == null) {
      return;
    }
    this.video = video;
    this.cameraOn = cameraOn;
    this.connected = connected;
    this.muted = muted;
    String name = target == null || target.isBlank() ? "通话" : target;
    titleLabel.setText(name);
    avatarLabel.setText(initial(name));
    statusLabel.setText(
        (video ? "视频通话" : "语音通话") + (connected ? " · 已接通" : " · 正在连接…"));
    if (muteBtn != null) {
      muteBtn.setText(muted ? "取消静音" : "静音");
      muteBtn.setStyle(muted ? dangerButtonStyle() : normalButtonStyle());
    }
    if (cameraBtn != null) {
      cameraBtn.setText(cameraOn ? "关闭摄像头" : "开启摄像头");
      cameraBtn.setStyle(cameraOn ? normalButtonStyle() : dangerButtonStyle());
      cameraBtn.setVisible(video);
      cameraBtn.setManaged(video);
    }
    if (avatarPane != null) {
      avatarPane.setVisible(!video);
      avatarPane.setManaged(!video);
    }
    if (stage != null) {
      stage.setAlwaysOnTop(video);
      stage.setWidth(360);
      stage.setHeight(video ? 250 : 470);
      stage.setTitle(video ? "SIP 视频通话控制" : "SIP 语音通话");
      if (video) {
        stage.toFront();
      }
    }
    if (connected) {
      startTimer();
    } else {
      stopTimer();
      timerLabel.setText("00:00");
    }
  }

  void setMuted(boolean muted) {
    this.muted = muted;
    if (muteBtn != null) {
      muteBtn.setText(muted ? "取消静音" : "静音");
      muteBtn.setStyle(muted ? dangerButtonStyle() : normalButtonStyle());
    }
  }

  void setCameraOn(boolean cameraOn) {
    this.cameraOn = cameraOn;
    if (cameraBtn != null) {
      cameraBtn.setText(cameraOn ? "关闭摄像头" : "开启摄像头");
      cameraBtn.setStyle(cameraOn ? normalButtonStyle() : dangerButtonStyle());
    }
  }

  void close() {
    stopTimer();
    if (stage != null) {
      stage.setOnCloseRequest(null);
      stage.close();
      stage = null;
    }
    muted = false;
    connected = false;
  }

  boolean isShowing() {
    return stage != null && stage.isShowing();
  }

  private void build(String target) {
    stage = new Stage();
    if (owner != null) {
      stage.initOwner(owner);
    }
    stage.setTitle("SIP 通话");
    stage.setResizable(false);

    avatarPane = new StackPane();
    Circle circle = new Circle(42, Color.web("#2481CC"));
    avatarLabel = new Label(initial(target));
    avatarLabel.setStyle("-fx-text-fill: white; -fx-font-size: 28px; -fx-font-weight: bold;");
    avatarPane.getChildren().addAll(circle, avatarLabel);

    titleLabel = new Label(target == null ? "通话" : target);
    titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold;");

    statusLabel = new Label("正在连接…");
    statusLabel.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 12px;");

    timerLabel = new Label("00:00");
    timerLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 16px; -fx-font-family: monospace;");

    muteBtn = new Button("静音");
    muteBtn.setStyle(normalButtonStyle());
    muteBtn.setOnAction(e -> onMute.accept(!muted));

    cameraBtn = new Button("关闭摄像头");
    cameraBtn.setStyle(normalButtonStyle());
    cameraBtn.setOnAction(e -> onCamera.accept(!cameraOn));

    hangupBtn = new Button("挂断");
    hangupBtn.setStyle(dangerButtonStyle());
    hangupBtn.setOnAction(e -> onHangup.run());

    HBox controls = new HBox(10, muteBtn, cameraBtn, hangupBtn);
    controls.setAlignment(Pos.CENTER);
    controls.setPadding(new Insets(8, 0, 0, 0));

    VBox root = new VBox(14, avatarPane, titleLabel, statusLabel, timerLabel, controls);
    root.setAlignment(Pos.CENTER);
    root.setPadding(new Insets(18));
    root.setStyle("-fx-background-color: #0F172A;");
    VBox.setVgrow(avatarPane, javafx.scene.layout.Priority.NEVER);

    Scene scene = new Scene(root, 360, 470);
    stage.setScene(scene);
    stage.setOnCloseRequest(
        e -> {
          e.consume();
          onHangup.run();
        });
  }

  private void startTimer() {
    if (timer != null) {
      return;
    }
    startedAt = System.currentTimeMillis();
    timer =
        new Timeline(
            new KeyFrame(
                Duration.seconds(1),
                e -> {
                  long sec = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
                  timerLabel.setText(String.format("%02d:%02d", sec / 60, sec % 60));
                }));
    timer.setCycleCount(Timeline.INDEFINITE);
    timer.play();
  }

  private void stopTimer() {
    if (timer != null) {
      timer.stop();
      timer = null;
    }
  }

  private static String initial(String value) {
    if (value == null || value.isBlank()) {
      return "?";
    }
    String s = value.trim();
    return s.substring(0, 1).toUpperCase();
  }

  private static String normalButtonStyle() {
    return "-fx-background-color: #1E293B; -fx-text-fill: #E2E8F0; -fx-font-size: 12px;"
        + " -fx-padding: 7 14 7 14; -fx-background-radius: 8;";
  }

  private static String dangerButtonStyle() {
    return "-fx-background-color: #DC2626; -fx-text-fill: white; -fx-font-size: 12px;"
        + " -fx-font-weight: bold; -fx-padding: 7 14 7 14; -fx-background-radius: 8;";
  }
}
