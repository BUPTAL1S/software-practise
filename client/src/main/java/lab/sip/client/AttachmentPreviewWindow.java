package lab.sip.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** One attachment preview. HTTP/file IO runs off JavaFX; the player belongs to this window. */
final class AttachmentPreviewWindow implements AutoCloseable {
  private final Stage stage = new Stage();
  private final Label notice = new Label("正在加载预览…");
  private final StackPane surface = new StackPane(notice);
  private final HBox tools = new HBox(10);
  private final ExecutorService loader = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "media-attachment-preview");
    thread.setDaemon(true);
    return thread;
  });
  private volatile boolean closed;
  private Path temporary;
  private MediaPlayer player;

  AttachmentPreviewWindow(Window owner, String name, String detail, boolean beforeSend, Runnable action) {
    if (owner != null) stage.initOwner(owner);
    if (beforeSend) stage.initModality(Modality.WINDOW_MODAL);
    String type = AttachmentMedia.kind(name) == AttachmentMedia.Kind.VIDEO ? "视频" : "图片";
    stage.setTitle(beforeSend ? "发送" + type + " · 预览" : type + "预览");
    stage.setMinWidth(420);
    stage.setMinHeight(330);

    Label filename = new Label(name);
    filename.setMaxWidth(Double.MAX_VALUE);
    filename.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
    Label subtitle = new Label(detail);
    subtitle.setStyle("-fx-text-fill: #64748b;");
    VBox header = new VBox(4, filename, subtitle);
    header.setPadding(new Insets(12, 16, 12, 16));
    notice.setWrapText(true);
    notice.setMaxWidth(430);
    notice.setPadding(new Insets(16));
    notice.setStyle("-fx-text-fill: #e2e8f0;");
    surface.setStyle("-fx-background-color: #0f172a;");
    surface.setMinSize(0, 0);

    tools.setAlignment(Pos.CENTER_LEFT);
    tools.setVisible(false);
    tools.setManaged(false);
    Button dismiss = new Button(beforeSend ? "取消" : "关闭");
    dismiss.setCancelButton(true);
    dismiss.getStyleClass().setAll("btn-secondary");
    dismiss.setOnAction(e -> close());
    Button primary = new Button(beforeSend ? "发送" : "下载文件");
    primary.getStyleClass().setAll(beforeSend ? "btn-primary" : "btn-secondary");
    primary.setOnAction(e -> {
      if (closed) return;
      if (beforeSend) close();
      else if (player != null) player.pause();
      action.run();
    });
    HBox actions = new HBox(8, dismiss, primary);
    actions.setAlignment(Pos.CENTER_RIGHT);
    VBox footer = new VBox(10, tools, actions);
    footer.setPadding(new Insets(12, 16, 12, 16));

    BorderPane root = new BorderPane(surface, header, null, footer, null);
    Scene scene = new Scene(root, 760, 550);
    scene.getStylesheets().add(getClass().getResource("/modern-theme.css").toExternalForm());
    scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) close(); });
    stage.setScene(scene);
    stage.setOnHidden(e -> release());
  }

  void show(String name, long size, Path local, HttpFileClient client, String url) {
    stage.show();
    AttachmentMedia.Kind kind = AttachmentMedia.kind(name);
    if (kind == AttachmentMedia.Kind.VIDEO && !AttachmentMedia.supportsVideo(name)) {
      showFailure("这种视频格式暂不支持预览。支持 H.264 MP4 / M4V，可发送或下载原文件。");
      return;
    }
    loader.execute(() -> {
      Path cached = null;
      boolean published = false;
      try {
        Path path = local;
        if (path == null || !Files.isRegularFile(path)) {
          if (client == null) throw new IOException("找不到选中的文件");
          if (size > AttachmentMedia.MAX_BYTES) throw new IOException("附件超过 64 MiB，请下载后查看");
          cached = Files.createTempFile("sip-media-preview-", "." + AttachmentMedia.extension(name));
          path = client.downloadPreview(url, cached, AttachmentMedia.MAX_BYTES);
        }
        if (Files.size(path) > AttachmentMedia.MAX_BYTES) throw new IOException("附件超过 64 MiB，无法预览");
        Image image = kind == AttachmentMedia.Kind.IMAGE ? AttachmentMedia.loadImage(path, 2048, 1536) : null;
        Path source = path;
        synchronized (this) {
          if (closed) return;
          temporary = cached;
          Platform.runLater(() -> {
            if (closed) return;
            if (image != null) showImage(image);
            else showVideo(source);
          });
          published = true;
        }
      } catch (Exception failure) {
        if (!closed) Platform.runLater(() -> showFailure(failure instanceof IOException
            ? failure.getMessage() : "无法加载预览，可尝试下载原文件。"));
      } finally {
        if (!published) deleteTemporary(cached);
      }
    });
  }

  private void showImage(Image image) {
    ImageView view = new ImageView(image);
    view.setPreserveRatio(true);
    view.setSmooth(true);
    Slider zoom = new Slider(1, 3, 1);
    zoom.setAccessibleText("图片缩放");
    zoom.setPrefWidth(170);
    Label percent = new Label();
    percent.textProperty().bind(Bindings.format("%.0f%%", zoom.valueProperty().multiply(100)));
    StackPane centered = new StackPane(view);
    ScrollPane scroll = new ScrollPane(centered);
    scroll.setPannable(true);
    scroll.setStyle("-fx-background: #0f172a; -fx-background-color: #0f172a;");
    centered.minWidthProperty().bind(Bindings.createDoubleBinding(
        () -> scroll.getViewportBounds().getWidth(), scroll.viewportBoundsProperty()));
    centered.minHeightProperty().bind(Bindings.createDoubleBinding(
        () -> scroll.getViewportBounds().getHeight(), scroll.viewportBoundsProperty()));
    view.fitWidthProperty().bind(Bindings.createDoubleBinding(
        () -> Math.max(1, scroll.getViewportBounds().getWidth() - 16) * zoom.getValue(),
        scroll.viewportBoundsProperty(), zoom.valueProperty()));
    view.fitHeightProperty().bind(Bindings.createDoubleBinding(
        () -> Math.max(1, scroll.getViewportBounds().getHeight() - 16) * zoom.getValue(),
        scroll.viewportBoundsProperty(), zoom.valueProperty()));
    surface.getChildren().setAll(scroll);
    tools.getChildren().setAll(new Label("缩放"), zoom, percent);
    showTools(true);
  }

  private void showVideo(Path source) {
    Runnable error = () -> showFailure("无法播放此视频。预览支持 H.264 MP4 / M4V，可下载后查看。");
    try {
      Media media = new Media(source.toUri().toString());
      MediaPlayer current = new MediaPlayer(media);
      player = current;
      MediaView view = new MediaView(current);
      view.setPreserveRatio(true);
      view.fitWidthProperty().bind(surface.widthProperty());
      view.fitHeightProperty().bind(surface.heightProperty());
      Button play = new Button("播放");
      play.getStyleClass().setAll("btn-secondary");
      play.setDisable(true);
      Slider progress = new Slider();
      progress.setAccessibleText("播放进度");
      progress.setDisable(true);
      HBox.setHgrow(progress, Priority.ALWAYS);
      Label clock = new Label("0:00 / 0:00");
      CheckBox mute = new CheckBox("静音");
      boolean[] ended = {false};
      mute.selectedProperty().addListener((obs, oldValue, value) -> current.setMute(value));
      Runnable updateClock = () -> clock.setText(formatTime(progress.getValue()) + " / " + formatTime(progress.getMax()));
      current.setOnReady(() -> {
        if (closed || player != current) return;
        Duration duration = current.getTotalDuration();
        if (duration != null && !duration.isUnknown() && !duration.isIndefinite() && duration.greaterThan(Duration.ZERO)) {
          progress.setMax(duration.toSeconds());
          progress.setDisable(false);
        }
        play.setDisable(false);
        updateClock.run();
        surface.getChildren().setAll(view);
      });
      current.currentTimeProperty().addListener((obs, oldValue, value) -> {
        if (!progress.isValueChanging()) progress.setValue(value.toSeconds());
      });
      progress.valueProperty().addListener((obs, oldValue, value) -> updateClock.run());
      Runnable seek = () -> {
        if (progress.isDisabled()) return;
        if (ended[0]) {
          ended[0] = false;
          current.pause();
          play.setText("播放");
        }
        current.seek(Duration.seconds(progress.getValue()));
      };
      progress.setOnMouseReleased(e -> seek.run());
      progress.setOnKeyReleased(e -> seek.run());
      current.setOnPlaying(() -> { if (!ended[0]) play.setText("暂停"); });
      current.setOnPaused(() -> play.setText(ended[0] ? "重播" : "播放"));
      current.setOnEndOfMedia(() -> { ended[0] = true; play.setText("重播"); });
      play.setOnAction(e -> {
        if (ended[0]) {
          ended[0] = false;
          current.seek(Duration.ZERO);
          current.play();
          play.setText("暂停");
        }
        else if (current.getStatus() == MediaPlayer.Status.PLAYING) current.pause();
        else current.play();
      });
      media.setOnError(error);
      current.setOnError(error);
      current.setOnHalted(error);
      notice.setText("正在准备视频…");
      tools.getChildren().setAll(play, progress, clock, mute);
      showTools(true);
      if (current.getError() != null || media.getError() != null) error.run();
    } catch (RuntimeException | LinkageError unavailable) { error.run(); }
  }

  private static String formatTime(double seconds) {
    long value = Math.max(0, (long) seconds);
    return String.format(Locale.ROOT, "%d:%02d", value / 60, value % 60);
  }

  private void showTools(boolean visible) {
    tools.setVisible(visible);
    tools.setManaged(visible);
  }

  private void showFailure(String message) {
    if (closed) return;
    disposePlayer();
    notice.setText(message == null || message.isBlank() ? "无法加载预览，可下载原文件。" : message);
    surface.getChildren().setAll(notice);
    tools.getChildren().clear();
    showTools(false);
  }

  private void disposePlayer() {
    if (player == null) return;
    MediaPlayer current = player;
    player = null;
    current.dispose();
  }

  @Override public void close() {
    release();
    stage.close();
  }

  private void release() {
    Path cached;
    synchronized (this) {
      if (closed) return;
      closed = true;
      cached = temporary;
      temporary = null;
    }
    loader.shutdownNow();
    try { disposePlayer(); }
    finally {
      surface.getChildren().clear();
      tools.getChildren().clear();
      if (cached != null) CompletableFuture.runAsync(() -> deleteTemporary(cached));
    }
  }

  private static void deleteTemporary(Path cached) {
    if (cached == null) return;
    try { Files.deleteIfExists(cached); }
    catch (IOException locked) { cached.toFile().deleteOnExit(); }
  }
}
