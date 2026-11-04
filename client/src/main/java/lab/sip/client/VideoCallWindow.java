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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  private final ExecutorService nativeWindowOps =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "video-window-native");
            thread.setDaemon(true);
            return thread;
          });
  private Stage stage;
  private boolean hangupRequested;
  private volatile boolean disposed;
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
    show(target, cameraOn, connected, false, onHangup, onMute, onCamera);
  }

  void show(
      String target,
      boolean cameraOn,
      boolean connected,
      boolean room,
      Runnable onHangup,
      Consumer<Boolean> onMute,
      Consumer<Boolean> onCamera) {
    this.onHangup = onHangup == null ? () -> {} : onHangup;
    this.onMute = onMute == null ? value -> {} : onMute;
    this.onCamera = onCamera == null ? value -> {} : onCamera;
    this.cameraOn = cameraOn;
    if (stage == null) {
      build(target, room);
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

  void syncElapsed(long elapsedNanos) {
    if (!connected) return;
    startedAt = System.nanoTime() - Math.max(0, elapsedNanos);
    if (timerLabel != null) timerLabel.setText(CallUi.duration(elapsedNanos));
  }

  void setMuted(boolean muted) {
    this.muted = muted;
    if (muteBtn != null) CallUi.state(muteBtn, muted ? "取消静音" : "静音", muted);
  }

  void setMediaBusy(boolean busy) {
    if (cameraBtn != null) cameraBtn.setDisable(busy);
    if (muteBtn != null) muteBtn.setDisable(busy);
  }

  /** Update only the camera affordance; safe to call before the next full sync. */
  void setCameraState(boolean enabled) {
    this.cameraOn = enabled;
    if (cameraBtn != null) {
      CallUi.state(cameraBtn, enabled ? "关闭摄像头" : "开启摄像头", !enabled);
    }
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
  private volatile NativeVideoPlacement.Bounds requestedBounds;
  private volatile long nativeParent;
  private final java.util.concurrent.atomic.AtomicBoolean layoutPending = new java.util.concurrent.atomic.AtomicBoolean();

  boolean attachNative(long remote, long pip) {
    if (disposed || stage == null || !stage.isShowing()) return false;
    if (remoteAvatarOff) remote = 0;
    if (remote == 0 && pip == 0) return false;
    long oldPip = pipHandle;
    if (pip == 0 && oldPip != 0) runNative(() -> WindowLayout.detach(0, oldPip));
    remoteHandle = remote; pipHandle = pip;
    var area = videoArea.localToScene(videoArea.getBoundsInLocal());
    requestedBounds = new NativeVideoPlacement.Bounds(0, remote, pip,
        (int)Math.round(area.getMinX()), (int)Math.round(area.getMinY()),
        Math.max(2, (int)Math.round(area.getWidth())), Math.max(2, (int)Math.round(area.getHeight())),
        stage.getOutputScaleX(), stage.getOutputScaleY());
    if (!layoutPending.compareAndSet(false, true)) return true;
    final String title = stage.getTitle();
    runNative(() -> {
      NativeVideoPlacement.Bounds bounds = null;
      try {
        do {
          bounds = requestedBounds;
          if (disposed || bounds == null) break;
          if (nativeParent == 0) nativeParent = WindowLayout.findWindow(title);
          final var requested = bounds;
          boolean applied = placement.apply(bounds, () -> WindowLayout.attach(nativeParent,
              requested.remote(), requested.pip(), requested.x(), requested.y(), requested.w(), requested.h(),
              requested.scaleX(), requested.scaleY()));
          if (applied) Platform.runLater(() -> {
            if (!disposed && requestedBounds == requested && requested.remote() != 0 && !remoteAvatarOff) showWaiting(false);
          });
          // Coalesce resize events while native parenting is busy; never build an unbounded queue.
        } while (bounds != requestedBounds && !disposed);
      } finally {
        layoutPending.set(false);
        // A new request can arrive between the loop condition and clearing the flag.
        if (!disposed && requestedBounds != null && requestedBounds != bounds) {
          Platform.runLater(this::layoutNative);
        }
      }
    });
    return true;
  }

  void detachPip() {
    requestedBounds = null;
    placement.invalidate();
    if (pipHandle != 0) {
      long oldPip = pipHandle;
      pipHandle = 0;
      runNative(() -> WindowLayout.detach(0, oldPip));
    }
  }

  /** Release SDL/JavaFX parenting before creating or restarting a native renderer. */
  void detachNative() {
    requestedBounds = null;
    placement.invalidate();
    long remote = remoteHandle;
    long pip = pipHandle;
    remoteHandle = 0;
    pipHandle = 0;
    if (remote != 0 || pip != 0) {
      runNative(() -> WindowLayout.detach(remote, pip));
    }
  }

  private void runNative(Runnable action) {
    if (disposed) {
      return;
    }
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac")) {
      if (Platform.isFxApplicationThread()) {
        if (!disposed) {
          action.run();
        }
      } else {
        Platform.runLater(
            () -> {
              if (!disposed) {
                action.run();
              }
            });
      }
    } else {
      try {
        nativeWindowOps.execute(
            () -> {
              if (!disposed) {
                action.run();
              }
            });
      } catch (java.util.concurrent.RejectedExecutionException ignored) {
        // Window already closed; a late layout task is harmless.
      }
    }
  }

  /** Hide the remote native video and show the peer avatar instead. */
  void setRemoteAvatarOff(boolean off, String peerName, javafx.scene.image.Image avatarImage) {
    setAvatar(avatarImage);
    setRemoteAvatarOff(off, peerName);
  }

  void setRemoteAvatarOff(boolean off, String peerName) {
    this.remoteAvatarOff = off;
    if (off) {
      requestedBounds = null;
      if (remoteHandle != 0) {
        placement.invalidate();
        long oldRemote = remoteHandle;
        remoteHandle = 0;
        runNative(() -> WindowLayout.detach(oldRemote, 0));
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

  /**
   * Hide the call surface but leave PJSIP's native children alone until the BYE
   * has torn them down. Detaching a live SDL child while PJSUA is hanging up is
   * the operation that made the JavaFX message pump unresponsive.
   */
  void hideForHangup() {
    hangupRequested = true;
    requestedBounds = null;
    placement.invalidate();
    stopTimer();
    if (stage != null) {
      stage.setOnCloseRequest(null);
      stage.hide();
    }
  }

  boolean isHangingUp() {
    return hangupRequested;
  }

  /** Close after the SIP call has ended; PJSIP owns and has destroyed the native children. */
  void closeWithoutDetach() {
    disposed = true;
    nativeWindowOps.shutdownNow();
    hangupRequested = false;
    placement.invalidate();
    stopTimer();
    remoteHandle = 0;
    pipHandle = 0;
    if (stage != null) {
      stage.setOnCloseRequest(null);
      stage.close();
      stage = null;
    }
  }

  void close() {
    disposed = true;
    nativeWindowOps.shutdownNow();
    boolean nativeAlreadyTornDown = hangupRequested;
    hangupRequested = false;
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
    if (nativeAlreadyTornDown || (remote == 0 && pip == 0)) {
      // PJSUA already destroyed the native video windows while processing BYE.
      // Touching those handles here is a use-after-free (0xC0000005).
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

  private void build(String target, boolean room) {
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
    videoArea.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
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
    stage.setResizable(true);
    stage.outputScaleXProperty().addListener((obs, oldV, newV) -> requestLayoutNative());
    stage.outputScaleYProperty().addListener((obs, oldV, newV) -> requestLayoutNative());
    stage.setOnCloseRequest(
        e -> {
          e.consume();
          onHangup.run();
        });
    videoArea
        .widthProperty()
        .addListener((obs, oldV, newV) -> requestLayoutNative());
    videoArea
        .heightProperty()
        .addListener((obs, oldV, newV) -> requestLayoutNative());
    stage.widthProperty().addListener((obs, oldV, newV) -> requestLayoutNative());
    stage.heightProperty().addListener((obs, oldV, newV) -> requestLayoutNative());
    stage.maximizedProperty().addListener((obs, oldV, newV) -> requestLayoutNative());
  }

  private boolean layoutScheduled;
  private void requestLayoutNative() {
    // Wait for BorderPane to finish the resize pass before measuring videoArea.
    if (layoutScheduled || disposed || hangupRequested) return;
    layoutScheduled = true;
    Platform.runLater(() -> Platform.runLater(() -> {
      layoutScheduled = false;
      layoutNative();
    }));
  }

  private void layoutNative() {
    if (!hangupRequested && (remoteHandle != 0 || pipHandle != 0)) attachNative(remoteHandle, pipHandle);
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
