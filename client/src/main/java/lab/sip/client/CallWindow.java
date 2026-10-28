package lab.sip.client;

import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** Full outgoing/connected voice-call surface. */
final class CallWindow {
  private final Window owner;
  private Stage stage;
  private javafx.scene.layout.StackPane avatar;
  private Label nameLabel, initialLabel, statusLabel, timerLabel;
  private Button muteBtn, cameraBtn, hangupBtn;
  private Timeline timer;
  private long startedAt;
  private boolean muted, cameraOn, connected;
  private Runnable onHangup = () -> {};
  private Consumer<Boolean> onMute = value -> {};
  private Consumer<Boolean> onCamera = value -> {};

  CallWindow(Window owner) { this.owner = owner; }

  void show(String target, boolean video, boolean cameraOn, boolean connected,
      Runnable onHangup, Consumer<Boolean> onMute, Consumer<Boolean> onCamera) {
    this.onHangup = onHangup == null ? () -> {} : onHangup;
    this.onMute = onMute == null ? value -> {} : onMute;
    this.onCamera = onCamera == null ? value -> {} : onCamera;
    if (stage == null) {
      build();
      stage.show();
      stage.toFront();
    }
    update(target, video, cameraOn, connected);
  }

  void update(String target, boolean video, boolean cameraOn, boolean connected) {
    if (stage == null) return;
    this.connected = connected;
    nameLabel.setText(CallUi.name(target));
    initialLabel.setText(CallUi.initial(target));
    refreshStatus();
    setCameraOn(cameraOn);
    cameraBtn.setVisible(video);
    cameraBtn.setManaged(video);
    CallUi.state(hangupBtn, connected ? "挂断" : "取消呼叫", false);
    timerLabel.setVisible(connected);
    if (connected) startTimer();
    // Keep user-chosen size and focus when call state refreshes.
  }

  void setMuted(boolean value) {
    muted = value;
    if (muteBtn != null) CallUi.state(muteBtn, value ? "取消静音" : "静音", value);
    if (statusLabel != null) refreshStatus();
  }

  private void refreshStatus() {
    statusLabel.setText(connected ? (muted ? "麦克风已静音" : "语音通话中") : "正在呼叫，等待对方接听…");
  }

  void setCameraOn(boolean value) {
    cameraOn = value;
    if (cameraBtn != null) CallUi.state(cameraBtn, value ? "关闭摄像头" : "开启摄像头", !value);
  }

  boolean isShowing() { return stage != null && stage.isShowing(); }

  void setAvatar(javafx.scene.image.Image image) { if (avatar != null) CallUi.photo(avatar, image); }

  void close() {
    if (timer != null) { timer.stop(); timer = null; }
    if (stage != null) {
      stage.setOnCloseRequest(null);
      stage.close();
      stage = null;
    }
    muted = false;
  }

  private void build() {
    stage = new Stage();
    if (owner != null) stage.initOwner(owner);
    stage.setTitle("语音通话");
    stage.setMinWidth(380);
    stage.setMinHeight(560);
    initialLabel = new Label();
    nameLabel = CallUi.label("", "call-name");
    nameLabel.setMaxWidth(330);
    statusLabel = CallUi.label("正在呼叫，等待对方接听…", "call-status");
    timerLabel = CallUi.label("00:00", "call-timer");
    avatar = CallUi.avatar(initialLabel);
    VBox identity = new VBox(18, avatar, nameLabel, statusLabel, timerLabel);
    identity.setAlignment(Pos.CENTER);
    muteBtn = CallUi.action(CallUi.Icon.MIC, "静音", "neutral", () -> onMute.accept(!muted));
    cameraBtn = CallUi.action(CallUi.Icon.VIDEO, "关闭摄像头", "neutral", () -> onCamera.accept(!cameraOn));
    hangupBtn = CallUi.action(CallUi.Icon.HANGUP, "取消呼叫", "end", () -> onHangup.run());
    HBox actions = new HBox(32, muteBtn, cameraBtn, hangupBtn);
    actions.setAlignment(Pos.CENTER);
    Region above = new Region(), below = new Region();
    VBox.setVgrow(above, Priority.ALWAYS);
    VBox.setVgrow(below, Priority.ALWAYS);
    VBox root = new VBox(18, CallUi.label("语音通话", "call-eyebrow"), above, identity, below,
        actions);
    root.setAlignment(Pos.CENTER);
    root.setPadding(new Insets(20, 24, 26, 24));
    root.getStyleClass().add("call-root");
    stage.setScene(CallUi.scene(root, 420, 620));
    stage.setOnCloseRequest(e -> { e.consume(); onHangup.run(); });
  }

  private void startTimer() {
    if (timer != null) return;
    startedAt = System.nanoTime();
    timer = new Timeline(new KeyFrame(Duration.seconds(1),
        e -> timerLabel.setText(CallUi.duration(System.nanoTime() - startedAt))));
    timer.setCycleCount(Timeline.INDEFINITE);
    timer.play();
  }
}
