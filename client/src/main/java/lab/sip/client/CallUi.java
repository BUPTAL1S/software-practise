package lab.sip.client;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Circle;

/** Shared call visuals, using local vector icons rather than platform-dependent emoji. */
final class CallUi {
  enum Icon {
    MIC("M9 2h6v12H9z M5 10v2a7 7 0 0 0 14 0v-2 M12 19v3 M8 22h8"),
    VIDEO("M3 5h12v14H3z M15 10l6-4v12l-6-4"),
    PHONE("M5 3l4 4-3 3c2 4 4 6 8 8l3-3 4 4-2 3C11 23 1 13 2 5z"),
    HANGUP("M3 15v-5c5-5 13-5 18 0v5h-5v-4a12 12 0 0 0-8 0v4z");
    final String path;
    Icon(String path) { this.path = path; }
  }

  private CallUi() {}

  static Scene scene(javafx.scene.Parent root, double width, double height) {
    Scene scene = new Scene(root, width, height);
    scene.getStylesheets().add(CallUi.class.getResource("/call-theme.css").toExternalForm());
    return scene;
  }

  static Label label(String text, String style) {
    Label label = new Label(text);
    label.getStyleClass().add(style);
    label.setAlignment(Pos.CENTER);
    label.setWrapText(true);
    return label;
  }

  static String name(String value) {
    return value == null || value.isBlank() ? "通话" : value.trim();
  }

  static String initial(String value) {
    String name = name(value);
    return name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
  }

  static StackPane avatar(Label initial) {
    initial.getStyleClass().add("call-avatar-initial");
    StackPane disk = new StackPane(initial);
    disk.getStyleClass().add("call-avatar-disk");
    disk.setMinSize(112, 112);
    disk.setMaxSize(112, 112);
    StackPane halo = new StackPane(disk);
    halo.getStyleClass().add("call-avatar-halo");
    halo.setMinSize(144, 144);
    halo.setMaxSize(144, 144);
    return halo;
  }

  static String duration(long nanos) {
    long sec = Math.max(0, nanos / 1_000_000_000L);
    return sec < 3600 ? String.format("%02d:%02d", sec / 60, sec % 60)
        : String.format("%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);
  }

  static void photo(StackPane avatar, Image image) {
    if (image == null) return;
    StackPane disk = (StackPane) avatar.getChildren().get(0);
    if (disk.getChildren().size() > 1) {
      ImageView previous = (ImageView) disk.getChildren().get(1);
      if (previous.getImage() == image) return;
      disk.getChildren().remove(1);
    }
    ImageView photo = new ImageView(image);
    photo.setFitWidth(112);
    photo.setFitHeight(112);
    photo.setClip(new Circle(56, 56, 56));
    photo.visibleProperty().bind(image.progressProperty().isEqualTo(1).and(image.errorProperty().not()));
    disk.getChildren().add(photo);
  }

  static Button action(Icon icon, String text, String tone, Runnable handler) {
    SVGPath path = new SVGPath();
    path.setContent(icon.path);
    path.getStyleClass().add("call-icon");
    StackPane disk = new StackPane(path);
    disk.getStyleClass().add("call-control-disc");
    disk.setMinSize(60, 60);
    disk.setMaxSize(60, 60);
    Button button = new Button(text, disk);
    button.setContentDisplay(ContentDisplay.TOP);
    button.setGraphicTextGap(10);
    button.setMinWidth(96);
    button.getStyleClass().addAll("call-control", tone);
    button.setOnAction(e -> handler.run());
    state(button, text, false);
    return button;
  }

  static void state(Button button, String text, boolean selected) {
    button.setText(text);
    button.setAccessibleText(text);
    button.setTooltip(new Tooltip(text));
    button.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), selected);
    StackPane disk = (StackPane) button.getGraphic();
    if (disk.getChildren().size() > 1) disk.getChildren().remove(1);
    if (selected) {
      SVGPath slash = new SVGPath();
      slash.setContent("M2 2L24 24");
      slash.getStyleClass().add("call-icon");
      disk.getChildren().add(slash);
    }
  }
}
