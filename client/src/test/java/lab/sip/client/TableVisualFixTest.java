package lab.sip.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
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
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
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

class TableVisualFixTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
    }
    Platform.setImplicitExit(false);
  }

  @Test
  void testTableFixVisualSnapshotAndPixelColors() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failureRef = new AtomicReference<>();

    Platform.runLater(
        () -> {
          try {
            ObservableList<HttpFileClient.CdrRecord> records = FXCollections.observableArrayList();
            records.add(new HttpFileClient.CdrRecord(4L, "1002", "1001", Instant.parse("2026-10-15T18:31:13.840Z"), 0L));
            records.add(new HttpFileClient.CdrRecord(3L, "1002", "1001", Instant.parse("2026-10-15T18:31:40.756Z"), 0L));
            records.add(new HttpFileClient.CdrRecord(2L, "1002", "1001", Instant.parse("2026-10-13T14:00:05Z"), 8L));
            records.add(new HttpFileClient.CdrRecord(1L, "1001", "1002", Instant.parse("2026-10-13T14:00:00Z"), 10L));

            TableView<HttpFileClient.CdrRecord> cdrTableView = new TableView<>(records);
            cdrTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            VBox.setVgrow(cdrTableView, Priority.ALWAYS);

            TableColumn<HttpFileClient.CdrRecord, Long> colId = new TableColumn<>("ID");
            colId.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().id()));
            colId.setMinWidth(45);
            colId.setPrefWidth(50);
            colId.setMaxWidth(60);

            TableColumn<HttpFileClient.CdrRecord, String> colCaller = new TableColumn<>("主叫");
            colCaller.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().caller()));
            colCaller.setMinWidth(75);
            colCaller.setPrefWidth(90);

            TableColumn<HttpFileClient.CdrRecord, String> colCallee = new TableColumn<>("被叫/房间");
            colCallee.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().calleeOrRoom()));
            colCallee.setMinWidth(90);
            colCallee.setPrefWidth(110);

            TableColumn<HttpFileClient.CdrRecord, String> colTime = new TableColumn<>("开始时间");
            colTime.setCellValueFactory(data -> {
              Instant t = data.getValue().startedAt();
              return new SimpleStringProperty(t != null ? t.toString().replace("T", " ").replace("Z", "") : "-");
            });
            colTime.setMinWidth(155);
            colTime.setPrefWidth(175);

            TableColumn<HttpFileClient.CdrRecord, String> colDur = new TableColumn<>("通话时长");
            colDur.setCellValueFactory(data -> {
              long d = data.getValue().durationSec();
              return new SimpleStringProperty(d > 0 ? (d + " 秒") : "0 秒 (未接通)");
            });
            colDur.setMinWidth(85);
            colDur.setPrefWidth(105);

            AtomicReference<Button> refChatBtn = new AtomicReference<>();
            AtomicReference<Button> refAudioBtn = new AtomicReference<>();
            AtomicReference<Button> refVideoBtn = new AtomicReference<>();

            TableColumn<HttpFileClient.CdrRecord, Void> colActions = new TableColumn<>("快捷操作");
            colActions.setMinWidth(165);
            colActions.setPrefWidth(175);
            colActions.setMaxWidth(200);
            colActions.setCellFactory(param -> new TableCell<>() {
              private final Button chatBtn = new Button("聊天");
              private final Button audioBtn = new Button("语音");
              private final Button videoBtn = new Button("视频");
              private final HBox box = new HBox(4, chatBtn, audioBtn, videoBtn);
              {
                setAlignment(Pos.CENTER);
                box.setAlignment(Pos.CENTER);
                chatBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 4 2 4;");
                chatBtn.getStyleClass().setAll("btn-secondary", "action-btn-small");
                chatBtn.setMinWidth(46);
                chatBtn.setPrefWidth(46);
                chatBtn.setMaxWidth(46);
                chatBtn.setTooltip(new Tooltip("切换至消息聊天窗口"));

                audioBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 4 2 4;");
                audioBtn.getStyleClass().setAll("btn-secondary", "action-btn-small");
                audioBtn.setMinWidth(46);
                audioBtn.setPrefWidth(46);
                audioBtn.setMaxWidth(46);
                audioBtn.setTooltip(new Tooltip("发起语音呼叫"));

                videoBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 4 2 4;");
                videoBtn.getStyleClass().setAll("btn-secondary", "action-btn-small");
                videoBtn.setMinWidth(46);
                videoBtn.setPrefWidth(46);
                videoBtn.setMaxWidth(46);
                videoBtn.setTooltip(new Tooltip("发起视频呼叫"));
              }
              @Override
              protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                  setGraphic(null);
                } else {
                  if (refChatBtn.get() == null) {
                    refChatBtn.set(chatBtn);
                    refAudioBtn.set(audioBtn);
                    refVideoBtn.set(videoBtn);
                  }
                  setGraphic(box);
                }
              }
            });

            cdrTableView.getColumns().addAll(colId, colCaller, colCallee, colTime, colDur, colActions);
            cdrTableView.getSelectionModel().select(0);

            VBox root = new VBox(10, cdrTableView);
            root.setPadding(new Insets(12));
            root.setStyle("-fx-background-color: #F8FAFC;");

            Scene scene = new Scene(root, 760, 480);
            scene.getStylesheets().add(getClass().getResource("/modern-theme.css").toExternalForm());

            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();

            // 1. Assert locked widths of chatBtn, audioBtn, videoBtn
            Button cBtn = refChatBtn.get();
            Button aBtn = refAudioBtn.get();
            Button vBtn = refVideoBtn.get();
            assertEquals(46.0, cBtn.getPrefWidth(), "chatBtn prefWidth must be locked to 46px");
            assertEquals(46.0, aBtn.getPrefWidth(), "audioBtn prefWidth must be locked to 46px");
            assertEquals(46.0, vBtn.getPrefWidth(), "videoBtn prefWidth must be locked to 46px");
            assertEquals(46.0, cBtn.getMinWidth(), "chatBtn minWidth must be locked to 46px");
            assertEquals(46.0, aBtn.getMinWidth(), "audioBtn minWidth must be locked to 46px");
            assertEquals(46.0, vBtn.getMinWidth(), "videoBtn minWidth must be locked to 46px");
            assertEquals(46.0, cBtn.getMaxWidth(), "chatBtn maxWidth must be locked to 46px");
            assertEquals(46.0, aBtn.getMaxWidth(), "audioBtn maxWidth must be locked to 46px");
            assertEquals(46.0, vBtn.getMaxWidth(), "videoBtn maxWidth must be locked to 46px");

            assertTrue(colActions.getWidth() >= 165.0, "colActions width must be at least 165px, actual: " + colActions.getWidth());
            assertTrue(colActions.getMinWidth() >= 165.0, "colActions minWidth must be at least 165px");

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

            File out = new File("/Users/developing/.gemini/antigravity-cli/brain/194ae225-4b5f-42bc-aa49-055e7cc3fb8c/snapshot_table_header_fixed.png");
            if (out.getParentFile() != null) {
              out.getParentFile().mkdirs();
            }
            ImageIO.write(bi, "png", out);
            System.out.println("FIXED_SNAPSHOT_SAVED: " + out.getAbsolutePath());

            // 2. Sample pixel colors on row 0 (selected row)
            // TableView header is roughly y: 12..40. Row 0 is roughly y: 45..75.
            int lightBlueCount = 0;
            int darkTextCount = 0;
            int pureWhiteCount = 0;
            double sumTextR = 0, sumTextG = 0, sumTextB = 0;
            double sumBgR = 0, sumBgG = 0, sumBgB = 0;

            for (int y = 45; y <= 75 && y < h; y++) {
              for (int x = 50; x <= 300 && x < w; x++) {
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

                // Assert zero pure white text pixels in the selected cell area
                if (r >= 253 && g >= 253 && b >= 253) {
                  pureWhiteCount++;
                }
              }
            }

            assertTrue(lightBlueCount > 500, "Selected row background must have light blue (#E0F2FE) pixels, actual: " + lightBlueCount);
            assertTrue(darkTextCount > 20, "Selected row text must have dark text (#0F172A) pixels, actual: " + darkTextCount);
            assertEquals(0, pureWhiteCount, "Selected row must not contain any pure white text pixels");

            // 3. Compute WCAG 2.1 relative luminance and contrast ratio
            double avgBgR = sumBgR / lightBlueCount;
            double avgBgG = sumBgG / lightBlueCount;
            double avgBgB = sumBgB / lightBlueCount;
            double avgTextR = sumTextR / darkTextCount;
            double avgTextG = sumTextG / darkTextCount;
            double avgTextB = sumTextB / darkTextCount;

            double ratio = contrastRatio(avgBgR, avgBgG, avgBgB, avgTextR, avgTextG, avgTextB);
            System.out.printf("WCAG_CONTRAST_RATIO: %.2f:1 (bg=rgb(%.1f,%.1f,%.1f), text=rgb(%.1f,%.1f,%.1f))%n",
                ratio, avgBgR, avgBgG, avgBgB, avgTextR, avgTextG, avgTextB);
            assertTrue(ratio >= 7.0, "Selected row contrast ratio must satisfy WCAG AAA >= 7.0:1, actual: " + ratio);

            stage.close();
          } catch (Throwable t) {
            failureRef.set(t);
          } finally {
            latch.countDown();
          }
        });

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Table snapshot generation timed out");
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
