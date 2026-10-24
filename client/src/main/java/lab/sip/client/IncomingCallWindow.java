package lab.sip.client;

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

/** Dedicated incoming-call prompt; replaces the old inline green banner. */
final class IncomingCallWindow {
  private Stage stage;
  private final Window owner;
  private Label nameLabel;
  private Label typeLabel;
  private Label initialLabel;
  private Button answerVideoBtn;
  private Button answerAudioBtn;
  private Button rejectBtn;
  private Runnable onAnswerVideo = () -> {};
  private Runnable onAnswerAudio = () -> {};
  private Runnable onReject = () -> {};

  IncomingCallWindow(Window owner) {
    this.owner = owner;
  }

  void show(
      String target,
      boolean video,
      Runnable answerVideo,
      Runnable answerAudio,
      Runnable reject) {
    this.onAnswerVideo = answerVideo == null ? () -> {} : answerVideo;
    this.onAnswerAudio = answerAudio == null ? () -> {} : answerAudio;
    this.onReject = reject == null ? () -> {} : reject;
    if (stage == null) {
      build();
    }
    nameLabel.setText(target == null || target.isBlank() ? "来电" : target);
    if (initialLabel != null) {
      String name = target == null || target.isBlank() ? "?" : target.trim();
      initialLabel.setText(name.substring(0, 1).toUpperCase());
    }
    typeLabel.setText(video ? "视频来电" : "语音来电");
    boolean showVideoAnswer = video;
    answerVideoBtn.setVisible(showVideoAnswer);
    answerVideoBtn.setManaged(showVideoAnswer);
    answerAudioBtn.setText(video ? "仅接听语音" : "接听");
    stage.setTitle(video ? "SIP 视频来电" : "SIP 语音来电");
    if (!stage.isShowing()) {
      stage.show();
    }
    stage.toFront();
    stage.requestFocus();
  }

  void close() {
    if (stage != null) {
      stage.setOnCloseRequest(null);
      stage.close();
      stage = null;
    }
  }

  boolean isShowing() {
    return stage != null && stage.isShowing();
  }

  private void build() {
    stage = new Stage();
    if (owner != null) {
      stage.initOwner(owner);
    }
    stage.setResizable(false);

    Circle avatar = new Circle(46, Color.web("#2481CC"));
    initialLabel = new Label("?");
    initialLabel.setStyle("-fx-text-fill: white; -fx-font-size: 30px; -fx-font-weight: bold;");
    StackPane avatarPane = new StackPane(avatar, initialLabel);

    nameLabel = new Label("来电");
    nameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold;");
    typeLabel = new Label("语音来电");
    typeLabel.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 13px;");

    answerVideoBtn = new Button("接听视频");
    answerVideoBtn.setStyle(
        "-fx-background-color: #16A34A; -fx-text-fill: white; -fx-font-size: 13px;"
            + " -fx-font-weight: bold; -fx-padding: 8 16 8 16; -fx-background-radius: 8;");
    answerVideoBtn.setOnAction(e -> onAnswerVideo.run());

    answerAudioBtn = new Button("接听");
    answerAudioBtn.setStyle(
        "-fx-background-color: #2563EB; -fx-text-fill: white; -fx-font-size: 13px;"
            + " -fx-font-weight: bold; -fx-padding: 8 16 8 16; -fx-background-radius: 8;");
    answerAudioBtn.setOnAction(e -> onAnswerAudio.run());

    rejectBtn = new Button("拒绝");
    rejectBtn.setStyle(
        "-fx-background-color: #DC2626; -fx-text-fill: white; -fx-font-size: 13px;"
            + " -fx-font-weight: bold; -fx-padding: 8 16 8 16; -fx-background-radius: 8;");
    rejectBtn.setOnAction(e -> onReject.run());

    HBox buttons = new HBox(10, answerVideoBtn, answerAudioBtn, rejectBtn);
    buttons.setAlignment(Pos.CENTER);
    buttons.setPadding(new Insets(10, 0, 0, 0));

    VBox root = new VBox(16, avatarPane, nameLabel, typeLabel, buttons);
    root.setAlignment(Pos.CENTER);
    root.setPadding(new Insets(24));
    root.setStyle("-fx-background-color: #0F172A;");
    stage.setScene(new Scene(root, 380, 440));
    stage.setOnCloseRequest(
        e -> {
          e.consume();
          onReject.run();
        });
  }
}
