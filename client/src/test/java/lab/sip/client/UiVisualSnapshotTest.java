package lab.sip.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UiVisualSnapshotTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
    }
    Platform.setImplicitExit(false);
  }

  private static void saveSnapshot(Scene scene, String filename) throws Exception {
    WritableImage image = scene.snapshot(null);
    int w = (int) image.getWidth();
    int h = (int) image.getHeight();
    BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    PixelReader pr = image.getPixelReader();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        bi.setRGB(x, y, pr.getArgb(x, y));
      }
    }
    File out = new File("/Users/developing/.gemini/antigravity-cli/brain/194ae225-4b5f-42bc-aa49-055e7cc3fb8c/" + filename);
    if (out.getParentFile() != null) {
      out.getParentFile().mkdirs();
    }
    ImageIO.write(bi, "png", out);
    System.out.println("SNAPSHOT_SAVED: " + out.getAbsolutePath());
  }

  @Test
  void testGenerateUiSnapshots() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failureRef = new AtomicReference<>();

    Platform.runLater(
        () -> {
          try {
            Stage stage = new Stage();
            MainViewAssembler assembler = new MainViewAssembler();

            // Build tabs
            TabPane tabPane = new TabPane();
            tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            VBox chatBox = new VBox(10);
            chatBox.getStyleClass().add("chat-container");
            ListView<String> chatList = new ListView<>();
            chatList.getItems().addAll(
                "1002: 你好，请问今天下午会议几点开始？",
                "我: 下午 14:30 在 MCU 研讨室 room-101 准时进行。",
                "1002: 好的收到，我会提前 5 分钟测试音视频设备。"
            );
            VBox.setVgrow(chatList, Priority.ALWAYS);
            chatBox.getChildren().addAll(new Label("与 1002 的对话"), chatList);

            Tab chatTab = new Tab("消息与文件", chatBox);
            Tab roomTab = new Tab("会议房间 (MCU)", new VBox());
            Tab cdrTab = new Tab("通话记录与统计", new VBox());

            LogView logs = new LogView();
            StackPane consoleHost = new StackPane(logs);
            logs.setupDockHost(consoleHost, stage);
            Tab consoleTab = new Tab("信令与控制台", consoleHost);

            tabPane.getTabs().addAll(chatTab, roomTab, cdrTab, consoleTab);

            BorderPane root =
                assembler.assembleProductUi(
                    stage,
                    new Label("已就绪"),
                    new Label("编解码器 H264/97 · 720p 30fps"),
                    new Label("后台已连接"),
                    new TextField("1002"),
                    new Button[] {
                      new Button("1001 · 正在运行"),
                      new Button("1002 · 测试账号 2"),
                      new Button("1003 · 测试账号 3"),
                      new Button("room-101 · MCU 会议")
                    },
                    new Button("视频呼叫"),
                    new Button("语音呼叫"),
                    new Button("挂断"),
                    new Button("摄像头预览"),
                    null,
                    new TextField("127.0.0.1"),
                    new Button("填入本机IP"),
                    new TextField("http://localhost:8080"),
                    new Button("测试后台"),
                    new javafx.scene.control.ComboBox<>(),
                    new TextField("lab"),
                    new TextField("1001"),
                    new PasswordField(),
                    new TextField("5062"),
                    new Button("启动引擎"),
                    new Button("注册/上线"),
                    new Button("登录后台"),
                    logs,
                    tabPane);

            Scene scene = new Scene(root, 1080, 750);
            try {
              scene.getStylesheets().add(getClass().getResource("/modern-theme.css").toExternalForm());
            } catch (Exception ignored) {
            }
            stage.setScene(scene);
            stage.show();

            // Populate mock SIP and app events
            logs.appendApp("系统启动完成，引擎绑定 127.0.0.1:5062");
            logs.append("注册", "正在向 Kamailio 发起 REGISTER");
            logs.appendSip(true, "REGISTER sip:1001@lab SIP/2.0", "REGISTER sip:1001@lab SIP/2.0\r\nVia: SIP/2.0/TCP 127.0.0.1:5062;branch=z9hG4bK-reg\r\nFrom: <sip:1001@lab>;tag=9912\r\nTo: <sip:1001@lab>\r\nCall-ID: reg-1001@127.0.0.1\r\nCSeq: 1 REGISTER\r\nContact: <sip:1001@127.0.0.1:5062;transport=tcp>\r\nExpires: 300\r\nContent-Length: 0\r\n\r\n");
            logs.appendSip(false, "SIP/2.0 200 OK", "SIP/2.0 200 OK\r\nVia: SIP/2.0/TCP 127.0.0.1:5062;branch=z9hG4bK-reg;received=127.0.0.1\r\nFrom: <sip:1001@lab>;tag=9912\r\nTo: <sip:1001@lab>;tag=kam-200\r\nCall-ID: reg-1001@127.0.0.1\r\nCSeq: 1 REGISTER\r\nContact: <sip:1001@127.0.0.1:5062;transport=tcp>;expires=300\r\nContent-Length: 0\r\n\r\n");
            logs.appendSip(true, "INVITE sip:1002@lab SIP/2.0", "INVITE sip:1002@lab SIP/2.0\r\nVia: SIP/2.0/TCP 127.0.0.1:5062\r\nFrom: <sip:1001@lab>;tag=call-1\r\nTo: <sip:1002@lab>\r\nCall-ID: call-12345\r\nCSeq: 101 INVITE\r\nContent-Type: application/sdp\r\n\r\nv=0\r\no=- 1001 1001 IN IP4 127.0.0.1\r\ns=Session\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 4000 RTP/AVP 0 8\r\nm=video 4002 RTP/AVP 97\r\na=rtpmap:97 H264/90000\r\n");
            logs.appendSip(false, "SIP/2.0 180 Ringing", "SIP/2.0 180 Ringing\r\nVia: SIP/2.0/TCP 127.0.0.1:5062\r\nFrom: <sip:1001@lab>;tag=call-1\r\nTo: <sip:1002@lab>;tag=call-2\r\nCall-ID: call-12345\r\nCSeq: 101 INVITE\r\nContent-Length: 0\r\n\r\n");
            logs.appendSip(false, "SIP/2.0 200 OK", "SIP/2.0 200 OK\r\nVia: SIP/2.0/TCP 127.0.0.1:5062\r\nFrom: <sip:1001@lab>;tag=call-1\r\nTo: <sip:1002@lab>;tag=call-2\r\nCall-ID: call-12345\r\nCSeq: 101 INVITE\r\nContent-Type: application/sdp\r\n\r\nv=0\r\no=- 1002 1002 IN IP4 127.0.0.1\r\ns=Session\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio 5000 RTP/AVP 0 8\r\nm=video 5002 RTP/AVP 97\r\na=rtpmap:97 H264/90000\r\n");
            logs.appendSip(true, "ACK sip:1002@127.0.0.1:5064 SIP/2.0", "ACK sip:1002@127.0.0.1:5064 SIP/2.0\r\nVia: SIP/2.0/TCP 127.0.0.1:5062\r\nCall-ID: call-12345\r\nCSeq: 101 ACK\r\nContent-Length: 0\r\n\r\n");

            // Snapshot 1: Tab 0 (Chat view - no drawer, 260px locked sidebar)
            saveSnapshot(scene, "snapshot_tab0_chat_no_drawer.png");

            // Snapshot 2: Tab 3 (Signaling console view in full size tab)
            tabPane.getSelectionModel().select(3);
            saveSnapshot(scene, "snapshot_tab3_console_fullscreen.png");

            // Snapshot 2b: Tab 3 with SIP packet detail panel open
            logs.selectRow(4);
            saveSnapshot(scene, "snapshot_tab3_console_with_detail.png");

            // Snapshot 3: Pop out to detached window
            logs.detachToWindow();
            saveSnapshot(scene, "snapshot_tab3_placeholder_when_detached.png");

            // Dock back
            logs.dockBack();
            saveSnapshot(scene, "snapshot_tab3_docked_back.png");

            stage.close();
          } catch (Throwable t) {
            failureRef.set(t);
          } finally {
            latch.countDown();
          }
        });

    assertTrue(latch.await(25, TimeUnit.SECONDS), "Snapshot generation timed out");
    if (failureRef.get() != null) {
      throw new AssertionError("Test failed inside JavaFX thread", failureRef.get());
    }
  }

  @Test
  void testFullClientInRoomMeetingSnapshot() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failureRef = new AtomicReference<>();

    Platform.runLater(
        () -> {
          try {
            Stage stage = new Stage();
            MainApp app = new MainApp();
            app.start(stage);

            // 1. Initial State Assertions (Idle)
            assertEquals("挂断", app.getHangupBtn().getText(), "Initial top bar button text must be '挂断'");
            assertFalse(app.getInRoomBanner().isVisible(), "Initial in-room banner must be hidden");

            // 2. Populate genuine room records and select room tab
            app.getRoomRecords().clear();
            app.getRoomRecords().add(new HttpFileClient.RoomInfo(101L, "公共研讨室 101", "sip:room-101@lab", 8, 3));
            app.getRoomRecords().add(new HttpFileClient.RoomInfo(102L, "多方会议室 102", "sip:room-102@lab", 8, 1));
            app.getRoomRecords().add(new HttpFileClient.RoomInfo(103L, "技术沙龙 103", "sip:room-103@lab", 16, 0));

            app.getRightTabPane().getSelectionModel().select(app.getRoomTab());

            // 3. Transition to In-Meeting State dynamically via SipService mock state
            app.getSip().setMockCallStateForTest("sip:room-101@lab", true);
            app.syncUiState();

            // 4. Assert dynamic state transition in real MainApp
            assertEquals("退出会议", app.getHangupBtn().getText(), "Top bar button must dynamically become '退出会议'");
            assertTrue(app.getHangupBtn().getStyleClass().contains("btn-danger"), "Top bar button must have btn-danger class");

            assertTrue(app.getInRoomBanner().isVisible(), "In-room banner must dynamically become visible");
            assertTrue(app.getInRoomBanner().isManaged(), "In-room banner must be managed when visible");
            String bannerText = app.getInRoomBannerLabel().getText();
            assertTrue(bannerText.contains("公共研讨室 101"), "Banner label must contain room name, actual: " + bannerText);
            assertTrue(bannerText.contains("视频参会 · 3×3 混流"), "Banner label must contain mode, actual: " + bannerText);

            assertEquals("退出当前会议", app.getInRoomExitBtn().getText(), "Banner exit button must be '退出当前会议'");
            assertTrue(app.getInRoomExitBtn().getStyleClass().contains("btn-danger"), "Banner exit button must have btn-danger class");

            // Select row 0 in real roomTableView
            app.getRoomTableView().getSelectionModel().select(0);
            app.getRoomTableView().refresh();

            // 5. Capture full client window snapshot of genuine MainApp
            Scene scene = stage.getScene();
            WritableImage image = scene.snapshot(null);
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            PixelReader pr = image.getPixelReader();
            for (int y = 0; y < h; y++) {
              for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, pr.getArgb(x, y));
              }
            }
            File out = new File("/Users/developing/.gemini/antigravity-cli/brain/194ae225-4b5f-42bc-aa49-055e7cc3fb8c/snapshot_full_client_in_room.png");
            if (out.getParentFile() != null) {
              out.getParentFile().mkdirs();
            }
            ImageIO.write(bi, "png", out);
            System.out.println("GENUINE_FULL_CLIENT_IN_ROOM_SNAPSHOT_SAVED: " + out.getAbsolutePath());

            // 6. Assert pixel colors on row 0 selection in genuine MainApp
            int lightBlueCount = 0;
            int darkTextCount = 0;
            int pureWhiteCount = 0;
            double sumTextR = 0, sumTextG = 0, sumTextB = 0;
            double sumBgR = 0, sumBgG = 0, sumBgB = 0;

            for (int y = 301; y <= 334 && y < h; y++) {
              for (int x = 355; x <= 700 && x < w; x++) {
                int argb = bi.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                if (Math.abs(r - 224) <= 15 && Math.abs(g - 242) <= 12 && Math.abs(b - 254) <= 12) {
                  lightBlueCount++;
                  sumBgR += r;
                  sumBgG += g;
                  sumBgB += b;
                }
                if (Math.abs(r - 15) <= 18 && Math.abs(g - 23) <= 18 && Math.abs(b - 42) <= 22) {
                  darkTextCount++;
                  sumTextR += r;
                  sumTextG += g;
                  sumTextB += b;
                }
                if (r >= 253 && g >= 253 && b >= 253) {
                  pureWhiteCount++;
                }
              }
            }
            assertTrue(lightBlueCount > 100, "Genuine MainApp table row selection background must have light blue (#E0F2FE) pixels, actual: " + lightBlueCount);
            assertTrue(darkTextCount > 20, "Genuine MainApp table row selection text must have dark text (#0F172A) pixels, actual: " + darkTextCount);
            assertEquals(0, pureWhiteCount, "Genuine MainApp selected cell area must not contain any pure white text pixels");

            double avgBgR = sumBgR / lightBlueCount;
            double avgBgG = sumBgG / lightBlueCount;
            double avgBgB = sumBgB / lightBlueCount;
            double avgTextR = sumTextR / darkTextCount;
            double avgTextG = sumTextG / darkTextCount;
            double avgTextB = sumTextB / darkTextCount;

            double ratio = contrastRatio(avgBgR, avgBgG, avgBgB, avgTextR, avgTextG, avgTextB);
            System.out.printf("FULL_CLIENT_WCAG_CONTRAST_RATIO: %.2f:1 (bg=rgb(%.1f,%.1f,%.1f), text=rgb(%.1f,%.1f,%.1f))%n",
                ratio, avgBgR, avgBgG, avgBgB, avgTextR, avgTextG, avgTextB);
            assertTrue(ratio >= 7.0, "Full client selected row contrast ratio must satisfy WCAG AAA >= 7.0:1, actual: " + ratio);

            // 7. Test Exit Meeting through UI button fire
            app.getHangupBtn().fire();
            app.syncUiState();

            assertEquals("挂断", app.getHangupBtn().getText(), "Top bar button text must dynamically revert to '挂断'");
            assertFalse(app.getInRoomBanner().isVisible(), "In-room banner must be hidden after exit");
            assertFalse(app.getSip().isInCall(), "SipService must no longer be in call");
            assertFalse(app.getSip().isInRoomCall(), "SipService must no longer be in room call");
            assertEquals("", app.getSip().currentCallTarget());

            stage.close();
          } catch (Throwable t) {
            failureRef.set(t);
          } finally {
            latch.countDown();
          }
        });

    assertTrue(latch.await(25, TimeUnit.SECONDS), "Full client snapshot generation timed out");
    if (failureRef.get() != null) {
      throw new AssertionError("Test failed inside JavaFX thread", failureRef.get());
    }
  }

  static double sRgbToLinear(double channel) {
    channel = channel / 255.0;
    return (channel <= 0.04045) ? (channel / 12.92) : Math.pow((channel + 0.055) / 1.055, 2.4);
  }

  static double relativeLuminance(double r, double g, double b) {
    double rLin = sRgbToLinear(r);
    double gLin = sRgbToLinear(g);
    double bLin = sRgbToLinear(b);
    return 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin;
  }

  static double contrastRatio(double r1, double g1, double b1, double r2, double g2, double b2) {
    double l1 = relativeLuminance(r1, g1, b1);
    double l2 = relativeLuminance(r2, g2, b2);
    double lighter = Math.max(l1, l2);
    double darker = Math.min(l1, l2);
    return (lighter + 0.05) / (darker + 0.05);
  }
}
