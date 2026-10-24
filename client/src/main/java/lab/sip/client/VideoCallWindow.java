package lab.sip.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import java.util.function.Consumer;

/**
 * Single-window WeChat-style video call.
 *
 * <p>The top/status bar and bottom controls are JavaFX. PJSIP's remote video
 * and local camera preview native windows are reparented into the black video
 * area, so the user sees one call window instead of separate native/control
 * windows.
 */
final class VideoCallWindow {
  private final Window owner;
  private Stage stage;
  private StackPane videoArea;
  private Label titleLabel;
  private Label statusLabel;
  private Label timerLabel;
  private Label placeholder;
  private StackPane avatarOverlay;
  private Label avatarInitial;
  private Label avatarHint;
  private boolean remoteAvatarOff;
  private Button muteBtn;
  private Button cameraBtn;
  private Button hangupBtn;
  private Timeline timer;
  private long startedAt;
  private long remoteHandle;
  private long pipHandle;
  private boolean cameraOn;
  private boolean muted;
  private boolean connected;
  private Runnable onHangup = () -> {};
  private Consumer<Boolean> onMute = value -> {};
  private Consumer<Boolean> onCamera = value -> {};

  VideoCallWindow(Window owner) {
    this.owner = owner;
  }

  void show(
      String target,
      boolean cameraOn,
      boolean connected,
      Runnable onHangup,
      Consumer<Boolean> onMute,
      Consumer<Boolean> onCamera) {
    this.onHangup = onHangup == null ? () -> {} : onHangup;
    this.onMute = onMute == null ? value -> {} : onMute;
    this.onCamera = onCamera == null ? value -> {} : onCamera;
    this.cameraOn = cameraOn;
    if (stage == null) {
      build(target);
      stage.show();
      stage.toFront();
      stage.requestFocus();
    }
    update(target, cameraOn, connected);
  }

  void update(String target, boolean cameraOn, boolean connected) {
    if (stage == null) {
      return;
    }
    this.cameraOn = cameraOn;
    this.connected = connected;
    String name = target == null || target.isBlank() ? "视频通话" : target;
    titleLabel.setText(name);
    statusLabel.setText(connected ? "视频通话 · 已接通" : "视频通话 · 正在连接…");
    if (muteBtn != null) {
      muteBtn.setText(muted ? "取消静音" : "静音");
      muteBtn.setStyle(muted ? dangerStyle() : normalStyle());
    }
    if (cameraBtn != null) {
      cameraBtn.setText(cameraOn ? "关闭摄像头" : "开启摄像头");
      cameraBtn.setStyle(cameraOn ? normalStyle() : dangerStyle());
    }
    if (placeholder != null) {
      placeholder.setVisible(!connected || (remoteHandle == 0 && !remoteAvatarOff));
      placeholder.setManaged(!connected || (remoteHandle == 0 && !remoteAvatarOff));
    }
    if (avatarOverlay != null) {
      avatarOverlay.setVisible(remoteAvatarOff);
      avatarOverlay.setManaged(remoteAvatarOff);
    }
    if (connected) {
      startTimer();
    } else {
      stopTimer();
      timerLabel.setText("00:00");
    }
    stage.toFront();
  }

  /** Reparent the PJSIP windows into this stage. Safe to call repeatedly. */
  boolean attachNative(long remote, long pip) {
    if (stage == null || !stage.isShowing() || remote == 0 || remoteAvatarOff) {
      return false;
    }
    if (!WindowLayout.isAvailable()) {
      if (placeholder != null) {
        placeholder.setText("本机未安装 callwindow 原生库，远端画面在独立窗口");
        placeholder.setVisible(true);
        placeholder.setManaged(true);
      }
      return false;
    }
    long parent = WindowLayout.findWindow(stage.getTitle());
    if (parent == 0) {
      return false;
    }
    if (pip == 0 && pipHandle != 0) {
      WindowLayout.detach(0, pipHandle);
      pipHandle = 0;
    }
    javafx.geometry.Bounds bounds = videoArea.localToScene(videoArea.getBoundsInLocal());
    int x = (int) Math.round(bounds.getMinX());
    int y = (int) Math.round(bounds.getMinY());
    int w = Math.max(2, (int) Math.round(bounds.getWidth()));
    int h = Math.max(2, (int) Math.round(bounds.getHeight()));
    WindowLayout.attach(parent, remote, pip, x, y, w, h);
    remoteHandle = remote;
    if (pip != 0) {
      pipHandle = pip;
    }
    if (placeholder != null) {
      placeholder.setVisible(false);
      placeholder.setManaged(false);
    }
    return true;
  }

  void detachPip() {
    if (pipHandle != 0) {
      WindowLayout.detach(0, pipHandle);
      pipHandle = 0;
    }
  }

  /** Hide the remote native video and show the peer avatar instead. */
  void setRemoteAvatarOff(boolean off, String peerName) {
    this.remoteAvatarOff = off;
    if (off) {
      if (remoteHandle != 0) {
        WindowLayout.detach(remoteHandle, 0);
        remoteHandle = 0;
      }
      if (avatarInitial != null) {
        String name = peerName == null || peerName.isBlank() ? "?" : peerName.trim();
        avatarInitial.setText(name.substring(0, 1).toUpperCase());
      }
    }
    if (avatarOverlay != null) {
      avatarOverlay.setVisible(off);
      avatarOverlay.setManaged(off);
    }
    if (placeholder != null) {
      placeholder.setVisible(!off && (remoteHandle == 0));
      placeholder.setManaged(!off && (remoteHandle == 0));
    }
  }

  void close() {
    stopTimer();
    if (remoteHandle != 0 || pipHandle != 0) {
      WindowLayout.detach(remoteHandle, pipHandle);
      remoteHandle = 0;
      pipHandle = 0;
    }
    if (stage != null) {
      stage.setOnCloseRequest(null);
      stage.close();
      stage = null;
    }
  }

  boolean isShowing() {
    return stage != null && stage.isShowing();
  }

  private void build(String target) {
    stage = new Stage();
    if (owner != null) {
      stage.initOwner(owner);
    }
    stage.setTitle("SIP 视频通话 - " + target + " - " + System.identityHashCode(this));
    stage.setMinWidth(560);
    stage.setMinHeight(400);

    titleLabel = new Label(target);
    titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;");
    statusLabel = new Label("正在连接…");
    statusLabel.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 12px;");
    timerLabel = new Label("00:00");
    timerLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 13px; -fx-font-family: monospace;");

    VBox titleBox = new VBox(2, titleLabel, statusLabel);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox topBar = new HBox(10, titleBox, spacer, timerLabel);
    topBar.setAlignment(Pos.CENTER_LEFT);
    topBar.setPadding(new Insets(8, 12, 8, 12));
    topBar.setStyle("-fx-background-color: #0F172A;");

    videoArea = new StackPane();
    videoArea.setStyle("-fx-background-color: #000000;");
    placeholder = new Label("正在连接视频…");
    placeholder.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 14px;");
    videoArea.getChildren().add(placeholder);

    Circle avatarCircle = new Circle(46, Color.web("#2481CC"));
    avatarInitial = new Label("?");
    avatarInitial.setStyle("-fx-text-fill: white; -fx-font-size: 30px; -fx-font-weight: bold;");
    StackPane avatar = new StackPane(avatarCircle, avatarInitial);
    avatarHint = new Label("对方已关闭摄像头");
    avatarHint.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 13px;");
    VBox avatarBox = new VBox(12, avatar, avatarHint);
    avatarBox.setAlignment(Pos.CENTER);
    avatarOverlay = new StackPane(avatarBox);
    avatarOverlay.setStyle("-fx-background-color: #0B1220;");
    avatarOverlay.setVisible(false);
    avatarOverlay.setManaged(false);
    videoArea.getChildren().add(avatarOverlay);

    muteBtn = new Button("静音");
    muteBtn.setStyle(normalStyle());
    muteBtn.setOnAction(e -> onMute.accept(!muted));

    cameraBtn = new Button("关闭摄像头");
    cameraBtn.setStyle(normalStyle());
    cameraBtn.setOnAction(e -> onCamera.accept(!cameraOn));

    hangupBtn = new Button("挂断");
    hangupBtn.setStyle(dangerStyle());
    hangupBtn.setOnAction(e -> onHangup.run());

    HBox controls = new HBox(10, muteBtn, cameraBtn, hangupBtn);
    controls.setAlignment(Pos.CENTER);
    controls.setPadding(new Insets(8));
    controls.setStyle("-fx-background-color: #0F172A;");

    BorderPane root = new BorderPane();
    root.setTop(topBar);
    root.setCenter(videoArea);
    root.setBottom(controls);
    root.setStyle("-fx-background-color: #0F172A;");

    stage.setScene(new Scene(root, 960, 640));
    stage.setOnCloseRequest(
        e -> {
          e.consume();
          onHangup.run();
        });
    videoArea
        .widthProperty()
        .addListener((obs, oldV, newV) -> Platform.runLater(this::layoutNative));
    videoArea
        .heightProperty()
        .addListener((obs, oldV, newV) -> Platform.runLater(this::layoutNative));
  }

  private void layoutNative() {
    if (remoteHandle != 0) {
      attachNative(remoteHandle, pipHandle);
    }
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

  private static String normalStyle() {
    return "-fx-background-color: #1E293B; -fx-text-fill: #E2E8F0; -fx-font-size: 12px;"
        + " -fx-padding: 7 14 7 14; -fx-background-radius: 8;";
  }

  private static String dangerStyle() {
    return "-fx-background-color: #DC2626; -fx-text-fill: white; -fx-font-size: 12px;"
        + " -fx-font-weight: bold; -fx-padding: 7 14 7 14; -fx-background-radius: 8;";
  }
}
