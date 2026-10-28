package lab.sip.client;

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

/** Incoming call with clear accept/reject actions and an audio-only alternative. */
final class IncomingCallWindow {
  private final Window owner;
  private Stage stage;
  private javafx.scene.layout.StackPane avatar;
  private Label nameLabel, typeLabel, initialLabel;
  private HBox actions;
  private Button audioOnly, acceptBtn;
  private boolean video, deciding;
  private Runnable onVideo = () -> {}, onAudio = () -> {}, onReject = () -> {};

  IncomingCallWindow(Window owner) { this.owner = owner; }

  void show(String target, boolean video, Runnable answerVideo, Runnable answerAudio, Runnable reject) {
    onVideo = answerVideo == null ? () -> {} : answerVideo;
    onAudio = answerAudio == null ? () -> {} : answerAudio;
    onReject = reject == null ? () -> {} : reject;
    this.video = video;
    if (stage == null) build();
    nameLabel.setText(CallUi.name(target));
    initialLabel.setText(CallUi.initial(target));
    typeLabel.setText(video ? "邀请你进行视频通话" : "邀请你进行语音通话");
    CallUi.state(acceptBtn, video ? "接听视频" : "接听", false);
    audioOnly.setVisible(video);
    audioOnly.setManaged(video);
    stage.setTitle(video ? "视频来电" : "语音来电");
    if (!stage.isShowing()) {
      stage.show();
      stage.toFront();
      stage.requestFocus();
    }
  }

  boolean isShowing() { return stage != null && stage.isShowing(); }

  void setAvatar(javafx.scene.image.Image image) { if (avatar != null) CallUi.photo(avatar, image); }

  void close() {
    if (stage != null) {
      stage.setOnCloseRequest(null);
      stage.close();
      stage = null;
    }
    deciding = false;
  }

  private void choose(Runnable action) {
    if (deciding) return;
    deciding = true;
    actions.setDisable(true);
    audioOnly.setDisable(true);
    action.run();
  }

  private void build() {
    stage = new Stage();
    if (owner != null) stage.initOwner(owner);
    stage.setMinWidth(380);
    stage.setMinHeight(560);
    initialLabel = new Label();
    nameLabel = CallUi.label("来电", "call-name");
    nameLabel.setMaxWidth(330);
    typeLabel = CallUi.label("邀请你进行通话", "call-status");
    avatar = CallUi.avatar(initialLabel);
    VBox identity = new VBox(20, avatar, nameLabel, typeLabel);
    identity.setAlignment(Pos.CENTER);
    Button decline = CallUi.action(CallUi.Icon.HANGUP, "拒绝", "end", () -> choose(onReject));
    acceptBtn = CallUi.action(video ? CallUi.Icon.VIDEO : CallUi.Icon.PHONE,
        "接听", "accept", () -> choose(this.video ? onVideo : onAudio));
    actions = new HBox(88, decline, acceptBtn);
    actions.setAlignment(Pos.CENTER);
    audioOnly = new Button("仅用语音接听");
    audioOnly.getStyleClass().add("call-audio-answer");
    audioOnly.setOnAction(e -> choose(onAudio));
    Region above = new Region(), below = new Region();
    VBox.setVgrow(above, Priority.ALWAYS);
    VBox.setVgrow(below, Priority.ALWAYS);
    VBox root = new VBox(14, CallUi.label("收到来电", "call-eyebrow"), above, identity,
        below, actions, audioOnly);
    root.setAlignment(Pos.CENTER);
    root.setPadding(new Insets(20, 24, 24, 24));
    root.getStyleClass().add("call-root");
    stage.setScene(CallUi.scene(root, 420, 620));
    stage.setOnCloseRequest(e -> { e.consume(); choose(onReject); });
  }
}
