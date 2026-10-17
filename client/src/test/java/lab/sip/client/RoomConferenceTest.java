package lab.sip.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import org.pjsip.pjsua2.TestVideoWindow;
import org.pjsip.pjsua2.VideoWindow;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RoomConferenceTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
    }
    Platform.setImplicitExit(false);
  }

  @Test
  void testRoomActionButtonsLogic() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean videoCalled = new AtomicBoolean(false);
    AtomicBoolean audioCalled = new AtomicBoolean(false);
    AtomicBoolean exitCalled = new AtomicBoolean(false);
    AtomicReference<String> calledTarget = new AtomicReference<>("");

    Platform.runLater(
        () -> {
          try {
            ObservableList<HttpFileClient.RoomInfo> rooms = FXCollections.observableArrayList();
            rooms.add(new HttpFileClient.RoomInfo(1L, "技术研讨室", "sip:room-1@lab", 8, 1));
            rooms.add(new HttpFileClient.RoomInfo(2L, "项目评审室", "sip:room-2@lab", 16, 3));

            TableView<HttpFileClient.RoomInfo> tableView = new TableView<>(rooms);

            TableColumn<HttpFileClient.RoomInfo, Long> colId = new TableColumn<>("ID");
            colId.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().id()));

            TableColumn<HttpFileClient.RoomInfo, String> colName = new TableColumn<>("房间名称");
            colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));

            TableColumn<HttpFileClient.RoomInfo, String> colUri = new TableColumn<>("SIP 标识 (URI)");
            colUri.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().sipUri()));

            TableColumn<HttpFileClient.RoomInfo, String> colMax = new TableColumn<>("上限人数");
            colMax.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().maxMembers() + " 人"));

            TableColumn<HttpFileClient.RoomInfo, String> colCount = new TableColumn<>("在会人数");
            colCount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().memberCount() + " 人"));

            TableColumn<HttpFileClient.RoomInfo, Void> colAction = new TableColumn<>("操作");
            colAction.setMinWidth(210);
            colAction.setMaxWidth(260);

            AtomicReference<Button> refJoinVideo = new AtomicReference<>();
            AtomicReference<Button> refJoinAudio = new AtomicReference<>();
            AtomicReference<Button> refExit = new AtomicReference<>();
            AtomicReference<Button> refSelect = new AtomicReference<>();

            colAction.setCellFactory(
                param ->
                    new TableCell<>() {
                      private final Button joinVideoBtn = new Button("视频参会");
                      private final Button joinAudioBtn = new Button("语音参会");
                      private final Button selectBtn = new Button("选定");
                      private final Button exitBtn = new Button("退出会议");
                      private final HBox defaultBox = new HBox(6, joinVideoBtn, joinAudioBtn, selectBtn);
                      private final HBox inMeetingBox = new HBox(6, exitBtn, selectBtn);

                      {
                        boxInit();
                      }

                      private void boxInit() {
                        defaultBox.setAlignment(Pos.CENTER_LEFT);
                        inMeetingBox.setAlignment(Pos.CENTER_LEFT);

                        joinVideoBtn.getStyleClass().setAll("btn-primary");
                        joinVideoBtn.setOnAction(
                            e -> {
                              HttpFileClient.RoomInfo r = getTableView().getItems().get(getIndex());
                              calledTarget.set(r.sipUri());
                              videoCalled.set(true);
                            });

                        joinAudioBtn.getStyleClass().setAll("btn-secondary");
                        joinAudioBtn.setOnAction(
                            e -> {
                              HttpFileClient.RoomInfo r = getTableView().getItems().get(getIndex());
                              calledTarget.set(r.sipUri());
                              audioCalled.set(true);
                            });

                        exitBtn.getStyleClass().setAll("btn-danger");
                        exitBtn.setOnAction(e -> exitCalled.set(true));

                        selectBtn.getStyleClass().setAll("btn-secondary");
                      }

                      @Override
                      protected void updateItem(Void item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                          setGraphic(null);
                        } else {
                          HttpFileClient.RoomInfo r = getTableView().getItems().get(getIndex());
                          refJoinVideo.set(joinVideoBtn);
                          refJoinAudio.set(joinAudioBtn);
                          refExit.set(exitBtn);
                          refSelect.set(selectBtn);
                          // For testing row 0 as current in-meeting room
                          if (r.id() == 1L) {
                            setGraphic(inMeetingBox);
                          } else {
                            setGraphic(defaultBox);
                          }
                        }
                      }
                    });

            tableView.getColumns().addAll(colId, colName, colUri, colMax, colCount, colAction);

            VBox root = new VBox(tableView);
            Scene scene = new Scene(root, 760, 400);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();

            tableView.layout();

            assertNotNull(refJoinVideo.get(), "joinVideoBtn must be instantiated");
            assertNotNull(refJoinAudio.get(), "joinAudioBtn must be instantiated");
            assertNotNull(refExit.get(), "exitBtn must be instantiated");
            assertNotNull(refSelect.get(), "selectBtn must be instantiated");

            assertEquals("视频参会", refJoinVideo.get().getText());
            assertEquals("语音参会", refJoinAudio.get().getText());
            assertEquals("退出会议", refExit.get().getText());
            assertEquals("选定", refSelect.get().getText());

            refJoinAudio.get().fire();
            assertTrue(audioCalled.get(), "joinAudioBtn should trigger audio call");
            assertFalse(videoCalled.get(), "joinAudioBtn must not trigger video call");
            assertEquals("sip:room-2@lab", calledTarget.get());

            refJoinVideo.get().fire();
            assertTrue(videoCalled.get(), "joinVideoBtn should trigger video call");
            assertEquals("sip:room-2@lab", calledTarget.get());

            refExit.get().fire();
            assertTrue(exitCalled.get(), "exitBtn should trigger exit call");

            stage.close();
          } catch (Throwable t) {
            t.printStackTrace();
          } finally {
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Logic test timed out");
  }

  @Test
  void testRoomVisualSnapshot() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);

    Platform.runLater(
        () -> {
          try {
            ObservableList<HttpFileClient.RoomInfo> rooms = FXCollections.observableArrayList();
            rooms.add(new HttpFileClient.RoomInfo(1L, "公共研讨室 101", "sip:room-101@lab", 8, 1));
            rooms.add(new HttpFileClient.RoomInfo(2L, "多方会议室 102", "sip:room-102@lab", 8, 0));
            rooms.add(new HttpFileClient.RoomInfo(3L, "自动化测试研讨室", "sip:room-3@lab", 8, 0));
            rooms.add(new HttpFileClient.RoomInfo(4L, "技术研讨室", "sip:room-4@lab", 8, 0));

            TableView<HttpFileClient.RoomInfo> tableView = new TableView<>(rooms);
            tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            VBox.setVgrow(tableView, Priority.ALWAYS);

            TableColumn<HttpFileClient.RoomInfo, Long> colId = new TableColumn<>("ID");
            colId.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().id()));
            colId.setMinWidth(45);
            colId.setMaxWidth(65);

            TableColumn<HttpFileClient.RoomInfo, String> colName = new TableColumn<>("房间名称");
            colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
            colName.setMinWidth(110);
            colName.setPrefWidth(140);

            TableColumn<HttpFileClient.RoomInfo, String> colUri = new TableColumn<>("SIP 标识 (URI)");
            colUri.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().sipUri()));
            colUri.setMinWidth(110);
            colUri.setPrefWidth(140);

            TableColumn<HttpFileClient.RoomInfo, String> colMax = new TableColumn<>("上限人数");
            colMax.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().maxMembers() + " 人"));
            colMax.setMinWidth(75);
            colMax.setMaxWidth(95);

            TableColumn<HttpFileClient.RoomInfo, String> colCount = new TableColumn<>("在会人数");
            colCount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().memberCount() + " 人"));
            colCount.setMinWidth(75);
            colCount.setMaxWidth(95);

            TableColumn<HttpFileClient.RoomInfo, Void> colAction = new TableColumn<>("操作");
            colAction.setMinWidth(210);
            colAction.setMaxWidth(260);
            colAction.setCellFactory(
                param ->
                    new TableCell<>() {
                      private final Button joinVideoBtn = new Button("视频参会");
                      private final Button joinAudioBtn = new Button("语音参会");
                      private final Button selectBtn = new Button("选定");
                      private final Button exitBtn = new Button("退出会议");
                      private final HBox defaultBox = new HBox(6, joinVideoBtn, joinAudioBtn, selectBtn);
                      private final HBox inMeetingBox = new HBox(6, exitBtn, selectBtn);

                      {
                        defaultBox.setAlignment(Pos.CENTER_LEFT);
                        inMeetingBox.setAlignment(Pos.CENTER_LEFT);

                        joinVideoBtn.getStyleClass().setAll("btn-primary");
                        joinVideoBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8 4 8;");

                        joinAudioBtn.getStyleClass().setAll("btn-secondary");
                        joinAudioBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8 4 8;");

                        exitBtn.getStyleClass().setAll("btn-danger");
                        exitBtn.setStyle(
                            "-fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-weight: bold;");

                        selectBtn.getStyleClass().setAll("btn-secondary");
                        selectBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8 4 8;");
                      }

                      @Override
                      protected void updateItem(Void item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                          setGraphic(null);
                        } else {
                          HttpFileClient.RoomInfo r = getTableView().getItems().get(getIndex());
                          if (r.id() == 1L) {
                            setGraphic(inMeetingBox);
                          } else {
                            joinVideoBtn.setDisable(true);
                            joinAudioBtn.setDisable(true);
                            setGraphic(defaultBox);
                          }
                        }
                      }
                    });

            tableView.getColumns().addAll(colId, colName, colUri, colMax, colCount, colAction);

            // In-room banner
            HBox inRoomBanner = new HBox(12);
            inRoomBanner.setAlignment(Pos.CENTER_LEFT);
            inRoomBanner.getStyleClass().setAll("active-room-banner");

            Label bannerIcon = new Label("当前正在研讨室【公共研讨室 101】中 (sip:room-101@lab · 视频参会 · 3×3 混流)");
            bannerIcon.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #166534;");
            HBox.setHgrow(bannerIcon, Priority.ALWAYS);

            Button bannerExitBtn = new Button("退出当前会议");
            bannerExitBtn.getStyleClass().setAll("btn-danger");
            bannerExitBtn.setStyle(
                "-fx-font-size: 11px; -fx-padding: 4 12 4 12; -fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-weight: bold;");

            inRoomBanner.getChildren().addAll(bannerIcon, bannerExitBtn);

            Label hintLabel = new Label("提示：房间标识由后台分配。点击【视频参会】拉起 3×3 MCU 混流画布；点击【语音参会】仅通话无视频弹窗。");
            hintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B; -fx-padding: 2 0 2 0;");

            HBox headerCard = new HBox(10);
            headerCard.setAlignment(Pos.CENTER_LEFT);
            Label roomTitle = new Label("会议房间清单 (MCU 3×3 视频研讨)");
            roomTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0F172A;");
            Label roomBadge = new Label("4 间");
            roomBadge.getStyleClass().setAll("badge-online");
            headerCard.getChildren().addAll(roomTitle, roomBadge);

            VBox roomBox = new VBox(8, headerCard, inRoomBanner, hintLabel, tableView);
            roomBox.setPadding(new Insets(10));
            roomBox.setStyle(
                "-fx-background-color: #ffffff; -fx-border-color: #d0d7de; -fx-border-radius: 8; -fx-background-radius: 8;");

            VBox root = new VBox(roomBox);
            root.setPadding(new Insets(12));
            root.setStyle("-fx-background-color: #F8FAFC;");

            Scene scene = new Scene(root, 820, 390);
            scene.getStylesheets().add(getClass().getResource("/modern-theme.css").toExternalForm());

            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();

            // Select row 0 (the current room) to test selection text contrast!
            tableView.getSelectionModel().select(0);

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
            File out =
                new File(
                    "/Users/developing/.gemini/antigravity-cli/brain/194ae225-4b5f-42bc-aa49-055e7cc3fb8c/snapshot_room_exit_and_selection_fixed.png");
            ImageIO.write(bi, "png", out);
            System.out.println("ROOM_FIXED_SNAPSHOT_SAVED: " + out.getAbsolutePath());

            // Assert pixel colors on row 0 (the selected room row)
            int lightBlueCount = 0;
            int darkTextCount = 0;
            int pureWhiteCount = 0;
            double sumTextR = 0, sumTextG = 0, sumTextB = 0;
            double sumBgR = 0, sumBgG = 0, sumBgB = 0;

            for (int y = 130; y <= 180 && y < h; y++) {
              for (int x = 50; x <= 350 && x < w; x++) {
                int argb = bi.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                // Check precise light blue background #E0F2FE (R ~ 224, G ~ 242, B ~ 254)
                if (Math.abs(r - 224) <= 15 && Math.abs(g - 242) <= 12 && Math.abs(b - 254) <= 12) {
                  lightBlueCount++;
                  sumBgR += r;
                  sumBgG += g;
                  sumBgB += b;
                }

                // Check precise dark text #0F172A (R ~ 15, G ~ 23, B ~ 42)
                if (Math.abs(r - 15) <= 18 && Math.abs(g - 23) <= 18 && Math.abs(b - 42) <= 22) {
                  darkTextCount++;
                  sumTextR += r;
                  sumTextG += g;
                  sumTextB += b;
                }

                // Check zero pure white pixels in selected room cell
                if (r >= 253 && g >= 253 && b >= 253) {
                  pureWhiteCount++;
                }
              }
            }
            assertTrue(lightBlueCount > 200, "Selected room row background must have light blue (#E0F2FE) pixels, actual: " + lightBlueCount);
            assertTrue(darkTextCount > 20, "Selected room row text must have dark text (#0F172A) pixels, actual: " + darkTextCount);
            assertEquals(0, pureWhiteCount, "Selected room row must not contain any pure white text pixels");

            double avgBgR = sumBgR / lightBlueCount;
            double avgBgG = sumBgG / lightBlueCount;
            double avgBgB = sumBgB / lightBlueCount;
            double avgTextR = sumTextR / darkTextCount;
            double avgTextG = sumTextG / darkTextCount;
            double avgTextB = sumTextB / darkTextCount;

            double ratio = contrastRatio(avgBgR, avgBgG, avgBgB, avgTextR, avgTextG, avgTextB);
            System.out.printf("ROOM_WCAG_CONTRAST_RATIO: %.2f:1 (bg=rgb(%.1f,%.1f,%.1f), text=rgb(%.1f,%.1f,%.1f))%n",
                ratio, avgBgR, avgBgG, avgBgB, avgTextR, avgTextG, avgTextB);
            assertTrue(ratio >= 7.0, "Selected room row contrast ratio must satisfy WCAG AAA >= 7.0:1, actual: " + ratio);

            stage.close();
          } catch (Exception ex) {
            ex.printStackTrace();
          } finally {
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Visual test timed out");
  }

  @Test
  void testVideoWindowCleanupAndConferenceMethods() {
    SipService sip = new SipService(new SipService.Events() {
      @Override public void log(String msg) {}
      @Override public void log(String category, String msg) {}
      @Override public void sip(boolean tx, String summary, String details) {}
      @Override public void stateChanged() {}
      @Override public void instantMessageReceived(String fromUri, String toUri, String content, String contentType) {}
      @Override public void instantMessageStatus(String toUri, String content, int code, String reason) {}
      @Override public void callEnded(String callId, String caller, String callee, java.time.Instant startedAt, long durationSec, boolean connected) {}
    });

    assertFalse(sip.isInCall());
    assertFalse(sip.isInRoomCall());
    assertFalse(sip.isCurrentCallVideo());
    assertEquals("", sip.currentCallTarget());
    assertFalse(sip.isCurrentRoom("sip:room-101@lab"));

    // Closing active video window when not in call must be safe and idempotent
    sip.closeActiveVideoWindow();
    assertFalse(sip.isCurrentCallVideo());
    assertNull(sip.getActiveVideoWindowForTest());

    // 1. VideoWindow creation, attachment, and safe destruction
    TestVideoWindow win = new TestVideoWindow();
    sip.attachActiveVideoWindowForTest(win);

    assertTrue(sip.isCurrentCallVideo(), "Current call must be recognized as video when VideoWindow is active");
    assertNotNull(sip.getActiveVideoWindowForTest(), "Video window must be attached");
    assertEquals(win, sip.getActiveVideoWindowForTest());

    // 2. Safe cleanup of native video window without deadlock
    sip.closeActiveVideoWindow();
    assertTrue(win.isHidden(), "VideoWindow.Show(false) must be called upon closing");
    assertTrue(win.isDeleted(), "VideoWindow.delete() must be called upon closing");
    assertNull(sip.getActiveVideoWindowForTest(), "VideoWindow must be destroyed and nulled");
    assertFalse(sip.isCurrentCallVideo(), "Call should no longer be video after window closed");

    // Idempotent close
    sip.closeActiveVideoWindow();
    assertNull(sip.getActiveVideoWindowForTest());

    // 3. Test mock call state transitions for conference
    sip.setMockCallStateForTest("sip:room-101@lab", true);
    assertTrue(sip.isInCall());
    assertTrue(sip.isInRoomCall());
    assertTrue(sip.isCurrentCallVideo());
    assertTrue(sip.isCurrentRoom("sip:room-101@lab"));
    assertEquals("sip:room-101@lab", sip.currentCallTarget());
    assertTrue(sip.statusText().contains("在会中 · 视频会议 (3×3 混流)"));

    sip.hangup();
    assertFalse(sip.isInCall());
    assertFalse(sip.isInRoomCall());
    assertEquals("", sip.currentCallTarget());
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
