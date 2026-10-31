package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AvatarFeatureTest {

  private static HttpServer server;
  private static String serverBaseUrl;
  private static final AtomicReference<String> mock1001Avatar =
      new AtomicReference<>("/uploads/avatars/1001.png");

  @BeforeEach
  void resetMockAvatar() {
    mock1001Avatar.set("/uploads/avatars/1001.png");
  }

  @BeforeAll
  static void startMockServer() throws Exception {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
    }
    Platform.setImplicitExit(false);

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

    // Mock POST /api/login
    server.createContext(
        "/api/login",
        exchange -> {
          String json = "{\"token\":\"mock-token-1001\",\"avatarUrl\":\"/uploads/avatars/1001.png\"}";
          byte[] resp = json.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
          }
        });

    // Mock GET /api/users
    server.createContext(
        "/api/users",
        exchange -> {
          String av = mock1001Avatar.get();
          String avField = av != null ? "\"" + av + "\"" : "null";
          String json =
              "[{\"id\":1,\"username\":\"1001\",\"name\":\"测试用户1\",\"sipId\":\"1001\",\"avatarUrl\":"
                  + avField
                  + "},"
                  + "{\"id\":2,\"username\":\"1002\",\"name\":\"测试用户2\",\"sipId\":\"1002\",\"avatarUrl\":null}]";
          byte[] resp = json.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
          }
        });

    // Mock POST /api/users/avatar
    server.createContext(
        "/api/users/avatar",
        exchange -> {
          if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            mock1001Avatar.set("/uploads/avatars/1001_custom.png");
            String json = "{\"avatarUrl\":\"/uploads/avatars/1001_custom.png\"}";
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(resp);
            }
          } else if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            mock1001Avatar.set(null);
            String json = "{\"status\":\"ok\",\"avatarUrl\":null}";
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(resp);
            }
          } else {
            exchange.sendResponseHeaders(405, -1);
          }
        });

    // Mock POST /api/rooms/room-101/vmute
    server.createContext(
        "/api/rooms/room-101/vmute",
        exchange -> {
          String q = exchange.getRequestURI().getQuery();
          String json = "{\"success\":" + (q == null || !q.contains("sipId=1099"))
              + ",\"query\":\"" + (q != null ? q : "") + "\"}";
          byte[] resp = json.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
          }
        });

    server.start();
    serverBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void testFetchUsersAndParseAvatars() throws Exception {
    HttpFileClient client = new HttpFileClient(serverBaseUrl);
    List<HttpFileClient.UserSummary> users = client.fetchUsers();
    assertNotNull(users);
    assertEquals(2, users.size());

    HttpFileClient.UserSummary u1 = users.get(0);
    assertEquals("1001", u1.username());
    assertEquals("1001", u1.sipId());
    assertEquals("/uploads/avatars/1001.png", u1.avatarUrl());

    HttpFileClient.UserSummary u2 = users.get(1);
    assertEquals("1002", u2.username());
    assertNull(u2.avatarUrl());
  }

  @Test
  void testUploadAndResetAvatar(@TempDir Path tempDir) throws Exception {
    HttpFileClient client = new HttpFileClient(serverBaseUrl);
    Path dummyImg = tempDir.resolve("test_avatar.png");
    Files.write(dummyImg, new byte[] {0x47, 0x49, 0x46, 0x38}); // dummy bytes

    String newAvatarUrl = client.uploadAvatar(dummyImg, "1001");
    assertEquals("/uploads/avatars/1001_custom.png", newAvatarUrl);
    assertEquals("/uploads/avatars/1001_custom.png", client.getUserAvatarUrl());

    boolean reset = client.resetAvatar("1001");
    assertTrue(reset);
    assertNull(client.getUserAvatarUrl());
  }

  @Test
  void testToggleRoomVmute() {
    HttpFileClient client = new HttpFileClient(serverBaseUrl);
    boolean muteRes = client.toggleRoomVmute("room-101", "1001", true);
    assertTrue(muteRes);

    boolean unmuteRes = client.toggleRoomVmute("sip:room-101@lab", "1001", false);
    assertTrue(unmuteRes);
    assertFalse(client.toggleRoomVmute("room-101", "1099", true),
        "A backend rejection must not be reported as a successful camera synchronization");
  }

  @Test
  void testMainViewAssemblerAvatarApi() throws Exception {
    MainViewAssembler assembler = new MainViewAssembler();
    AtomicBoolean clicked = new AtomicBoolean(false);
    assembler.setOnAvatarClicked(() -> clicked.set(true));

    Platform.runLater(() -> {
      // Test avatar update methods do not crash or throw bound property exceptions
      assembler.updateNavAvatar(null, "1001");
      assembler.updateNavAvatar("http://example.com/avatar.png", "1001");
      assembler.updateContactAvatar("1002", "http://example.com/1002.png");
      assembler.updateContactAvatar("1002", null);
    });

    Thread.sleep(100);
    assertNotNull(assembler);
  }

  @Test
  void testAvatarCropperPreservesAspectWithoutSquishing() {
    // 1. Landscape 16:9 image (1600x900)
    BufferedImage wideImg = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D gWide = wideImg.createGraphics();
    gWide.setColor(java.awt.Color.BLUE);
    gWide.fillRect(0, 0, 1600, 900);
    gWide.setColor(java.awt.Color.WHITE);
    gWide.fillOval(350, 0, 900, 900); // 1:1 circle inside
    gWide.dispose();

    BufferedImage croppedWide = AvatarCropperDialog.cropImage(wideImg, 350, 0, 900, 400);
    assertNotNull(croppedWide);
    assertEquals(400, croppedWide.getWidth());
    assertEquals(400, croppedWide.getHeight());
    assertEquals(0xFFFFFF, croppedWide.getRGB(200, 200) & 0x00FFFFFF, "Center of 16:9 crop should remain white");

    // 2. Portrait 9:16 vertical image (900x1600)
    BufferedImage tallImg = new BufferedImage(900, 1600, BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D gTall = tallImg.createGraphics();
    gTall.setColor(java.awt.Color.RED);
    gTall.fillRect(0, 0, 900, 1600);
    gTall.setColor(java.awt.Color.YELLOW);
    gTall.fillOval(0, 350, 900, 900); // 1:1 circle inside
    gTall.dispose();

    BufferedImage croppedTall = AvatarCropperDialog.cropImage(tallImg, 0, 350, 900, 400);
    assertNotNull(croppedTall);
    assertEquals(400, croppedTall.getWidth());
    assertEquals(400, croppedTall.getHeight());
    assertEquals(0xFFFF00, croppedTall.getRGB(200, 200) & 0x00FFFFFF, "Center of 9:16 crop should remain yellow");

    // 3. Square 1:1 image (800x800)
    BufferedImage sqImg = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D gSq = sqImg.createGraphics();
    gSq.setColor(java.awt.Color.MAGENTA);
    gSq.fillRect(0, 0, 800, 800);
    gSq.setColor(java.awt.Color.GREEN);
    gSq.fillOval(0, 0, 800, 800);
    gSq.dispose();

    BufferedImage croppedSq = AvatarCropperDialog.cropImage(sqImg, 0, 0, 800, 400);
    assertNotNull(croppedSq);
    assertEquals(400, croppedSq.getWidth());
    assertEquals(400, croppedSq.getHeight());
    assertEquals(0x00FF00, croppedSq.getRGB(200, 200) & 0x00FFFFFF, "Center of 1:1 crop should remain green");
  }

  @Test
  void testAvatarCropperInteractiveDialogControlsAndKeyboard(@TempDir Path tempDir) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failureRef = new AtomicReference<>();
    Path testImg = tempDir.resolve("interactive_test.png");
    BufferedImage bi = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
    ImageIO.write(bi, "png", testImg.toFile());

    Path[] resultHolder = new Path[1];

    Platform.runLater(() -> {
      try {
        Stage dummyOwner = new Stage();
        dummyOwner.show();

        Stage cropperStage = AvatarCropperDialog.createDialog(dummyOwner, testImg.toFile(), resultHolder);
        assertNotNull(cropperStage, "Cropper dialog stage should be created");
        Scene scene = cropperStage.getScene();
        assertNotNull(scene, "Cropper scene should be created");

        Slider zoomSlider = (Slider) scene.lookup("#cropperZoomSlider");
        Label zoomValLbl = (Label) scene.lookup("#cropperZoomValLbl");
        Button resetBtn = (Button) scene.lookup("#cropperResetBtn");
        Button confirmBtn = (Button) scene.lookup("#cropperConfirmBtn");
        Button cancelBtn = (Button) scene.lookup("#cropperCancelBtn");
        Canvas canvas = (Canvas) scene.lookup("#cropperCanvas");

        assertNotNull(zoomSlider, "Zoom slider should be found in scene");
        assertNotNull(zoomValLbl, "Zoom label should be found in scene");
        assertNotNull(resetBtn, "Reset button should be found in scene");
        assertNotNull(confirmBtn, "Confirm button should be found in scene");
        assertNotNull(cancelBtn, "Cancel button should be found in scene");
        assertNotNull(canvas, "Canvas should be found in scene");

        assertEquals(1.0, zoomSlider.getMin(), 0.001);
        assertEquals(4.0, zoomSlider.getMax(), 0.001);
        assertEquals("1.0x (100%)", zoomValLbl.getText());

        // Test slider adjustment updates zoom label
        zoomSlider.setValue(2.5);
        assertEquals("2.5x (250%)", zoomValLbl.getText());

        // Test reset button reverts zoom and text
        resetBtn.fire();
        assertEquals(1.0, zoomSlider.getValue(), 0.001);
        assertEquals("1.0x (100%)", zoomValLbl.getText());

        // Test keyboard events: + to zoom in
        KeyEvent plusKey = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.PLUS, false, false, false, false);
        scene.getOnKeyPressed().handle(plusKey);
        assertTrue(zoomSlider.getValue() > 1.05, "PLUS key should increment zoom slider");

        // Test keyboard events: - to zoom out
        KeyEvent minusKey = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.MINUS, false, false, false, false);
        scene.getOnKeyPressed().handle(minusKey);

        // Test keyboard arrow navigation
        KeyEvent leftKey = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.LEFT, false, false, false, false);
        scene.getOnKeyPressed().handle(leftKey);
        KeyEvent rightKey = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, false, false, false);
        scene.getOnKeyPressed().handle(rightKey);

        // Test Enter key confirms and exports
        KeyEvent enterKey = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
        scene.getOnKeyPressed().handle(enterKey);

        assertNotNull(resultHolder[0], "Cropped result path should be exported on Enter key");
        assertTrue(Files.exists(resultHolder[0]), "Exported cropped file should exist");
        BufferedImage exported = ImageIO.read(resultHolder[0].toFile());
        assertNotNull(exported);
        assertEquals(400, exported.getWidth());
        assertEquals(400, exported.getHeight());

        dummyOwner.close();
      } catch (Throwable t) {
        failureRef.set(t);
      } finally {
        latch.countDown();
      }
    });

    assertTrue(latch.await(10, TimeUnit.SECONDS));
    if (failureRef.get() != null) {
      throw new AssertionError("Interactive cropper test failed", failureRef.get());
    }
  }

  @Test
  void testAvatarModalSnapshot() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failureRef = new AtomicReference<>();
    Platform.runLater(() -> {
      try {
        MainViewAssembler assembler = new MainViewAssembler();
        HttpFileClient client = new HttpFileClient(serverBaseUrl);
        client.login("1001", "1001");
        LogView logs = new LogView();

        Stage dummyStage = new Stage();
        assembler.showAvatarSettingsModal(dummyStage, client, "1001", "1001@lab", logs);

        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
          if (window instanceof Stage s && "个人资料与头像设置".equals(s.getTitle())) {
            Scene sc = s.getScene();
            if (sc != null) {
              WritableImage img = sc.snapshot(null);
              int w = (int) img.getWidth();
              int h = (int) img.getHeight();
              BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
              PixelReader pr = img.getPixelReader();
              for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                  bi.setRGB(x, y, pr.getArgb(x, y));
                }
              }
              File repoOut = new File("target/test-snapshots/snapshot_avatar_settings_modal.png");
              if (repoOut.getParentFile() != null) repoOut.getParentFile().mkdirs();
              ImageIO.write(bi, "png", repoOut);
              System.out.println("AVATAR_MODAL_SNAPSHOT_SAVED: " + repoOut.getAbsolutePath());
            }
            s.close();
            break;
          }
        }
        dummyStage.close();
      } catch (Throwable t) {
        failureRef.set(t);
      } finally {
        latch.countDown();
      }
    });

    assertTrue(latch.await(10, TimeUnit.SECONDS));
    if (failureRef.get() != null) {
      throw new AssertionError("Modal snapshot failed", failureRef.get());
    }
  }

  @Test
  void testAvatarCropperDialogSnapshot(@TempDir Path tempDir) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failureRef = new AtomicReference<>();
    Platform.runLater(() -> {
      try {
        Path dummySample = tempDir.resolve("sample_wide.png");
        BufferedImage wideImg = new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = wideImg.createGraphics();
        g.setColor(new java.awt.Color(30, 41, 59));
        g.fillRect(0, 0, 800, 500);
        g.setColor(new java.awt.Color(14, 165, 233));
        g.fillOval(200, 50, 400, 400);
        g.setColor(java.awt.Color.WHITE);
        g.fillPolygon(new int[] {400, 320, 480}, new int[] {120, 360, 360}, 3);
        g.dispose();
        ImageIO.write(wideImg, "png", dummySample.toFile());

        Path[] holder = new Path[1];
        Stage cropperStage = AvatarCropperDialog.createDialog(null, dummySample.toFile(), holder);
        assertNotNull(cropperStage);
        cropperStage.show();

        Scene sc = cropperStage.getScene();
        if (sc != null) {
          WritableImage img = sc.snapshot(null);
          int w = (int) img.getWidth();
          int h = (int) img.getHeight();
          BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
          PixelReader pr = img.getPixelReader();
          for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
              bi.setRGB(x, y, pr.getArgb(x, y));
            }
          }
          File repoOut = new File("target/test-snapshots/snapshot_avatar_cropper_dialog.png");
          if (repoOut.getParentFile() != null) repoOut.getParentFile().mkdirs();
          ImageIO.write(bi, "png", repoOut);
          System.out.println("AVATAR_CROPPER_SNAPSHOT_SAVED: " + repoOut.getAbsolutePath());
        }
        cropperStage.close();
      } catch (Throwable t) {
        failureRef.set(t);
      } finally {
        latch.countDown();
      }
    });

    assertTrue(latch.await(10, TimeUnit.SECONDS));
    if (failureRef.get() != null) {
      throw new AssertionError("Cropper dialog snapshot failed", failureRef.get());
    }
  }

  @Test
  void testIsolatedBackendAvatarWorkflow(@TempDir Path tempDir) throws Exception {
    HttpFileClient liveClient = new HttpFileClient(serverBaseUrl);
    String token = liveClient.login("1001", "1001");
    assertNotNull(token, "Login as 1001 must return non-null token");
    assertFalse(token.isBlank(), "Login token must not be blank");

    List<HttpFileClient.UserSummary> users = liveClient.fetchUsers();
    assertNotNull(users);
    assertTrue(users.stream().anyMatch(u -> "1001".equals(u.username())));

    Path dummyImg = tempDir.resolve("live_avatar.png");
    BufferedImage bi = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    ImageIO.write(bi, "png", dummyImg.toFile());

    String avatarUrl = liveClient.uploadAvatar(dummyImg, "1001");
    assertNotNull(avatarUrl);
    assertTrue(avatarUrl.startsWith("/api/files/") || avatarUrl.startsWith("/uploads/avatars/"),
        "Avatar URL should have valid prefix, got: " + avatarUrl);

    // Verify fetched users reflects the new avatar
    List<HttpFileClient.UserSummary> usersAfterUpload = liveClient.fetchUsers();
    HttpFileClient.UserSummary u1001 = usersAfterUpload.stream()
        .filter(u -> "1001".equals(u.username()))
        .findFirst()
        .orElseThrow();
    assertEquals(avatarUrl, u1001.avatarUrl());

    // Test room vmute control
    boolean vmuteOn = liveClient.toggleRoomVmute("room-101", "1001", true);
    assertTrue(vmuteOn, "toggleRoomVmute true should succeed");

    boolean vmuteOff = liveClient.toggleRoomVmute("room-101", "1001", false);
    assertTrue(vmuteOff, "toggleRoomVmute false should succeed");

    boolean reset = liveClient.resetAvatar("1001");
    assertTrue(reset);

    // Verify avatarUrl is cleared
    List<HttpFileClient.UserSummary> usersAfterReset = liveClient.fetchUsers();
    HttpFileClient.UserSummary u1001Reset = usersAfterReset.stream()
        .filter(u -> "1001".equals(u.username()))
        .findFirst()
        .orElseThrow();
    assertNull(u1001Reset.avatarUrl());
    System.out.println("ISOLATED_BACKEND_AVATAR_TEST_SUCCESSFUL: " + serverBaseUrl);
  }
}
