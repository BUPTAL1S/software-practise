package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AttachmentPreviewWindowTest {
  @BeforeAll static void startFx() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    fx(() -> { Platform.setImplicitExit(false); return null; });
  }

  @Test void imageCanBeZoomedAndCancelledBeforeUpload(@TempDir Path directory) throws Exception {
    Path image = directory.resolve("测试图片.png");
    ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
    AtomicInteger uploads = new AtomicInteger();
    String title = "发送图片 · 预览";
    AttachmentPreviewWindow preview = fx(() -> showImage(image, uploads));
    try {
      await(() -> fx(() -> find(stage(title), ImageView.class) != null));
      fx(() -> {
        Stage stage = stage(title);
        stage.setRenderScaleX(1.25);
        stage.setRenderScaleY(1.25);
        stage.setMaximized(true);
        return null;
      });
      await(() -> fx(() -> stage(title).isMaximized() && stage(title).getScene().getWidth() > 760));
      assertStableFit(title);
      fx(() -> { stage(title).setMaximized(false); return null; });
      await(() -> fx(() -> !stage(title).isMaximized() && stage(title).getScene().getWidth() <= 770));
      fx(() -> {
        Stage stage = stage(title);
        stage.setWidth(535);
        stage.setHeight(690);
        stage.setRenderScaleX(1.5);
        stage.setRenderScaleY(1.5);
        return null;
      });
      await(() -> fx(() -> stage(title).getScene().getWidth() <= 535 && stage(title).getScene().getHeight() > 620));
      assertStableFit(title);
      fx(() -> {
        Stage stage = stage(title);
        assertEquals(0, uploads.get());
        ImageView view = find(stage, ImageView.class);
        Slider zoom = find(stage, Slider.class);
        assertNotNull(zoom);
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        double initialWidth = view.getFitWidth();
        assertTrue(initialWidth > 100);
        zoom.setValue(2);
        assertEquals(2 * initialWidth, view.getFitWidth(), 1.1);
        button(stage, "取消").fire();
        assertEquals(0, uploads.get());
        assertFalse(stage.isShowing());
        return null;
      });
    } finally { fx(() -> { preview.close(); return null; }); }

    fx(() -> {
      try (var confirming = showImage(image, uploads)) {
        button(stage(title), "发送").fire();
        assertEquals(1, uploads.get());
      }
      return null;
    });
  }

  private static void assertStableFit(String title) throws Exception {
    double[] last = null;
    for (int i = 0; i < 6; i++) {
      Thread.sleep(45);
      double[] current = fx(() -> {
        Stage stage = stage(title);
        var pane = find(stage, ScrollPane.class);
        var image = find(stage, ImageView.class);
        var viewport = pane.getViewportBounds();
        var content = pane.getContent().getLayoutBounds();
        assertTrue(image.getBoundsInLocal().getWidth() <= viewport.getWidth() + 1);
        assertTrue(image.getBoundsInLocal().getHeight() <= viewport.getHeight() + 1);
        assertTrue(content.getWidth() <= viewport.getWidth() + 1);
        assertTrue(content.getHeight() <= viewport.getHeight() + 1);
        return new double[]{viewport.getWidth(), viewport.getHeight(), image.getFitWidth(), image.getFitHeight()};
      });
      if (last != null) assertArrayEquals(last, current, 0.01, "Fit must settle across pulses");
      last = current;
    }
  }

  @Test @EnabledOnOs({OS.WINDOWS, OS.MAC})
  void fetchedMp4PlaysPausesSeeksAndCleansUpAfterClose() throws Exception {
    byte[] video;
    // Synthetic H.264 test pattern with a silent AAC track, generated from FFmpeg testsrc2 and anullsrc.
    try (var fixture = getClass().getResourceAsStream("/attachment-preview.mp4")) {
      assertNotNull(fixture);
      video = fixture.readAllBytes();
    }
    HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    backend.createContext("/api/files/57", exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "video/mp4");
      exchange.sendResponseHeaders(200, video.length);
      try (var output = exchange.getResponseBody()) { output.write(video); }
    });
    backend.start();
    String title = "视频预览";
    var preview = fx(() -> {
      var window = new AttachmentPreviewWindow(null, "测试视频.mp4", "测试", false, () -> fail("预览不应调用下载入口"));
      window.show("测试视频.mp4", video.length, null,
          new HttpFileClient("http://127.0.0.1:" + backend.getAddress().getPort()), "/api/files/57");
      return window;
    });
    try {
      await(() -> fx(() -> find(stage(title), MediaView.class) != null));
      MediaPlayer player = fx(() -> find(stage(title), MediaView.class).getMediaPlayer());
      Path cached = fx(() -> Path.of(URI.create(player.getMedia().getSource())));
      assertTrue(Files.isRegularFile(cached));
      Button toggle = fx(() -> button(stage(title), "播放"));
      fx(() -> { toggle.fire(); return null; });
      await(() -> fx(() -> player.getStatus() == MediaPlayer.Status.PLAYING
          && "暂停".equals(toggle.getText()) && player.getCurrentTime().toMillis() >= 200));
      fx(() -> { toggle.fire(); return null; });
      await(() -> fx(() -> player.getStatus() == MediaPlayer.Status.PAUSED));
      fx(() -> {
        Slider position = find(stage(title), Slider.class);
        position.setValue(1.6);
        position.getOnKeyReleased().handle(null);
        return null;
      });
      await(() -> fx(() -> Math.abs(player.getCurrentTime().toSeconds() - 1.6) < .3));
      fx(() -> { button(stage(title), "关闭").fire(); return null; });
      assertEquals(MediaPlayer.Status.DISPOSED, fx(player::getStatus));
      await(() -> !Files.exists(cached));
    } finally {
      fx(() -> { preview.close(); return null; });
      backend.stop(0);
    }
  }

  @Test @EnabledOnOs(OS.WINDOWS)
  void javaFxFailureFallsBackToBundledSoftwarePlayback(@TempDir Path directory) throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(SoftwareVideoPreview.supportedPlatform());
    Path video = directory.resolve("兼容播放 #1.mp4");
    try (var input = getClass().getResourceAsStream("/attachment-preview.mp4")) {
      assertNotNull(input);
      Files.copy(input, video);
    }
    String title = "视频预览";
    var preview = fx(() -> {
      String name = video.getFileName().toString();
      var window = new AttachmentPreviewWindow(null, name, "测试", false, () -> fail("不得调用下载"));
      window.show(name, Files.size(video), video, null, null);
      return window;
    });
    try {
      await(() -> fx(() -> find(stage(title), MediaView.class) != null || find(stage(title), ImageView.class) != null));
      fx(() -> {
        var nativeView = find(stage(title), MediaView.class);
        if (nativeView != null) nativeView.getMediaPlayer().getOnError().run();
        return null;
      });
      await(() -> fx(() -> find(stage(title), ImageView.class) != null));
      Button toggle = fx(() -> button(stage(title), "播放"));
      Slider progress = fx(() -> find(stage(title), Slider.class));
      fx(() -> { toggle.fire(); return null; });
      await(() -> fx(() -> "暂停".equals(toggle.getText()) && progress.getValue() > 0.2));
      fx(() -> { toggle.fire(); return null; });
      await(() -> fx(() -> "播放".equals(toggle.getText())));
      fx(() -> {
        progress.setValue(2.1);
        progress.getOnKeyReleased().handle(null);
        return null;
      });
      Thread.sleep(160);
      await(() -> fx(() -> Math.abs(progress.getValue() - 2.1) < .3));
      fx(() -> { button(stage(title), "关闭").fire(); return null; });
      await(() -> {
        try { return !Files.exists(video) || Files.deleteIfExists(video); }
        catch (java.io.IOException stillOpen) { return false; }
      });
    } finally { fx(() -> { preview.close(); return null; }); }
  }

  private static AttachmentPreviewWindow showImage(Path image, AtomicInteger uploads) {
    String name = image.getFileName().toString();
    var preview = new AttachmentPreviewWindow(null, name, "发送给 1002", true, uploads::incrementAndGet);
    preview.show(name, 0, image, null, null);
    return preview;
  }

  private static Stage stage(String title) {
    return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
        .filter(s -> title.equals(s.getTitle())).findFirst().orElseThrow();
  }

  private static Button button(Stage stage, String label) {
    return stage.getScene().getRoot().lookupAll("*").stream().filter(Button.class::isInstance)
        .map(Button.class::cast).filter(b -> label.equals(b.getText())).findFirst().orElseThrow();
  }

  private static <T extends Node> T find(Stage stage, Class<T> type) {
    return find(stage.getScene().getRoot(), type);
  }

  private static <T extends Node> T find(Node node, Class<T> type) {
    if (type.isInstance(node)) return type.cast(node);
    if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
      T result = find(child, type);
      if (result != null) return result;
    }
    return null;
  }

  private static void await(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      if (condition.call()) return;
      Thread.sleep(30);
    } while (System.nanoTime() < deadline);
    fail("预览未在 10 秒内到达预期状态");
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    CompletableFuture<T> result = new CompletableFuture<>();
    Platform.runLater(() -> {
      try { result.complete(action.call()); }
      catch (Throwable error) { result.completeExceptionally(error); }
    });
    return result.get(10, TimeUnit.SECONDS);
  }
}
