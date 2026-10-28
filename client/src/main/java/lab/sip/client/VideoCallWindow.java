package lab.sip.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
  private VBox waitingPane;
  private Label waitingInitial;
  private Label waitingName;
  private Label avatarName;
  private StackPane waitingAvatar;
  private StackPane peerAvatar;
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
  private final NativeVideoPlacement placement = new NativeVideoPlacement();
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
    statusLabel.setText(connected ? "视频通话中" : "正在呼叫，等待对方接听…");
    waitingInitial.setText(CallUi.initial(name));
    waitingName.setText(name);
    avatarName.setText(name);
    if (muteBtn != null) {
      CallUi.state(muteBtn, muted ? "取消静音" : "静音", muted);
    }
    if (cameraBtn != null) {
      CallUi.state(cameraBtn, cameraOn ? "关闭摄像头" : "开启摄像头", !cameraOn);
    }
    if (placeholder != null) {
      placeholder.setText(connected ? "正在加载对方画面…" : "等待对方接听视频邀请…");
      showWaiting(!connected || (remoteHandle == 0 && !remoteAvatarOff));
    }
    if (avatarOverlay != null) {
      avatarOverlay.setVisible(remoteAvatarOff);
      avatarOverlay.setManaged(remoteAvatarOff);
    }
    CallUi.state(hangupBtn, connected ? "挂断" : "取消呼叫", false);
    timerLabel.setVisible(connected);
    if (connected) {
      startTimer();
    } else {
      stopTimer();
      timerLabel.setText("00:00");
    }
  }

  void setMuted(boolean muted) {
    this.muted = muted;
    if (muteBtn != null) CallUi.state(muteBtn, muted ? "取消静音" : "静音", muted);
  }

  void setMediaBusy(boolean busy) {
    if (cameraBtn != null) cameraBtn.setDisable(busy);
    if (muteBtn != null) muteBtn.setDisable(busy);
  }

  void setAvatar(javafx.scene.image.Image image) {
    if (waitingAvatar != null) CallUi.photo(waitingAvatar, image);
    if (peerAvatar != null) CallUi.photo(peerAvatar, image);
  }

  private void showWaiting(boolean visible) {
    // Every path that reveals this pane must use the current call state, including
    // avatar/camera changes that do not go through update().
    placeholder.setText(connected ? "通话已接通，正在等待对方视频画面…" : "等待对方接听视频邀请…");
    waitingPane.setVisible(visible);
    waitingPane.setManaged(visible);
  }

  void setAvatarHint(String hint) {
    if (avatarHint != null) avatarHint.setText(hint);
  }

  /** Reparent the PJSIP windows into this stage. Safe to call repeatedly. */
  boolean attachNative(long remote, long pip) {
    if (stage == null || !stage.isShowing() || remote == 0 || remoteAvatarOff) {
      return false;
    }
    if (!WindowLayout.isAvailable()) {
      if (placeholder != null) {
        showWaiting(true);
        placeholder.setText("视频画面已在独立窗口中打开");
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
    NativeVideoPlacement.Bounds requested = new NativeVideoPlacement.Bounds(
        parent, remote, pip, x, y, w, h, stage.getOutputScaleX(), stage.getOutputScaleY());
    if (!placement.apply(requested,
        () -> WindowLayout.attach(parent, remote, pip, x, y, w, h))) {
      return false;
    }
    remoteHandle = remote;
    if (pip != 0) {
      pipHandle = pip;
    }
    if (placeholder != null) {
      showWaiting(false);
    }
    return true;
  }

  void detachPip() {
    placement.invalidate();
    if (pipHandle != 0) {
      WindowLayout.detach(0, pipHandle);
      pipHandle = 0;
    }
  }

  /** Release SDL/JavaFX parenting before creating or restarting a native renderer. */
  void detachNative() {
    placement.invalidate();
    if (remoteHandle != 0 || pipHandle != 0) WindowLayout.detach(remoteHandle, pipHandle);
    remoteHandle = 0;
    pipHandle = 0;
  }

  /** Hide the remote native video and show the peer avatar instead. */
  void setRemoteAvatarOff(boolean off, String peerName, javafx.scene.image.Image avatarImage) {
    setAvatar(avatarImage);
    setRemoteAvatarOff(off, peerName);
  }

  void setRemoteAvatarOff(boolean off, String peerName) {
    this.remoteAvatarOff = off;
    if (off) {
      if (remoteHandle != 0) {
        placement.invalidate();
        WindowLayout.detach(remoteHandle, 0);
        remoteHandle = 0;
      }
      if (avatarInitial != null) {
        avatarInitial.setText(CallUi.initial(peerName));
      }
    }
    if (avatarOverlay != null) {
      avatarOverlay.setVisible(off);
      avatarOverlay.setManaged(off);
    }
    if (placeholder != null) {
      showWaiting(!off && remoteHandle == 0);
    }
  }

  void close() {
    placement.invalidate();
    stopTimer();
    final Stage closing = stage;
    stage = null;
    final long remote = remoteHandle;
    final long pip = pipHandle;
    remoteHandle = 0;
    pipHandle = 0;
    if (closing != null) {
      closing.setOnCloseRequest(null);
      closing.hide();
    }
    if (remote == 0 && pip == 0) {
      if (closing != null) {
        closing.close();
      }
      return;
    }
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac")) {
      // Cocoa window operations must stay on the JavaFX/AppKit thread.
      WindowLayout.detach(remote, pip);
      if (closing != null) {
        closing.close();
      }
      return;
    }
    // SetParent on a live SDL child can block while Windows is pumping messages.
    // Hide the JavaFX window first, detach off the FX thread, then close it.
    new Thread(
            () -> {
              WindowLayout.detach(remote, pip);
              Platform.runLater(
                  () -> {
                    if (closing != null) {
                      closing.close();
                    }
                  });
            },
            "video-window-detach")
        .start();
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
    stage.setMinWidth(640);
    stage.setMinHeight(540);

    titleLabel = CallUi.label(CallUi.name(target), "video-name");
    titleLabel.setMaxWidth(380);
    statusLabel = CallUi.label("正在呼叫，等待对方接听…", "call-subtle");
    timerLabel = CallUi.label("00:00", "call-timer");

    VBox titleBox = new VBox(2, titleLabel, statusLabel);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox topBar = new HBox(10, titleBox, spacer, timerLabel);
    topBar.setAlignment(Pos.CENTER_LEFT);
    topBar.getStyleClass().add("video-header");

    videoArea = new StackPane();
    videoArea.getStyleClass().add("video-surface");
    placeholder = CallUi.label("等待对方接听视频邀请…", "call-status");
    waitingInitial = new Label(CallUi.initial(target));
    waitingName = CallUi.label(CallUi.name(target), "call-name");
    waitingName.setMaxWidth(420);
    waitingAvatar = CallUi.avatar(waitingInitial);
    waitingPane = new VBox(18, waitingAvatar, waitingName, placeholder);
    waitingPane.setAlignment(Pos.CENTER);
    waitingPane.setPadding(new Insets(24));
    videoArea.getChildren().add(waitingPane);

    avatarInitial = new Label("?");
    peerAvatar = CallUi.avatar(avatarInitial);
    avatarName = CallUi.label(CallUi.name(target), "call-name");
    avatarName.setMaxWidth(420);
    avatarHint = CallUi.label("对方已关闭摄像头", "call-status");
    VBox avatarBox = new VBox(18, peerAvatar, avatarName, avatarHint);
    avatarBox.setAlignment(Pos.CENTER);
    avatarBox.setPadding(new Insets(24));
    avatarOverlay = new StackPane(avatarBox);
    avatarOverlay.getStyleClass().add("video-surface");
    avatarOverlay.setVisible(false);
    avatarOverlay.setManaged(false);
    videoArea.getChildren().add(avatarOverlay);

    muteBtn = CallUi.action(CallUi.Icon.MIC, "静音", "neutral", () -> onMute.accept(!muted));
    cameraBtn = CallUi.action(CallUi.Icon.VIDEO, "关闭摄像头", "neutral", () -> onCamera.accept(!cameraOn));
    hangupBtn = CallUi.action(CallUi.Icon.HANGUP, "挂断", "end", () -> onHangup.run());

    HBox controls = new HBox(32, muteBtn, cameraBtn, hangupBtn);
    controls.setAlignment(Pos.CENTER);
    controls.getStyleClass().add("video-controls");

    BorderPane root = new BorderPane();
    root.setTop(topBar);
    root.setCenter(videoArea);
    root.setBottom(controls);
    root.getStyleClass().addAll("call-root", "video-root");

    stage.setScene(CallUi.scene(root, 1000, 740));
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
    startedAt = System.nanoTime();
    timer =
        new Timeline(
            new KeyFrame(
                Duration.seconds(1),
                e -> {
                  timerLabel.setText(CallUi.duration(System.nanoTime() - startedAt));
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

}
