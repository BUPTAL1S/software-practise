package lab.sip.client;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Interactive avatar cropper dialog.
 * Allows panning, zooming and precisely cropping an image into a 1:1 circular avatar,
 * eliminating aspect-ratio squishing and distortions.
 */
public class AvatarCropperDialog {

  private static final double CANVAS_WIDTH = 420;
  private static final double CANVAS_HEIGHT = 380;
  private static final double CROP_SIZE = 240; // Diameter of circular crop guide
  private static final int OUTPUT_SIZE = 400; // Final 1:1 output pixel resolution

  /**
   * Opens the interactive cropper dialog on the given image file.
   *
   * @param owner Parent stage
   * @param sourceFile Selected source image file
   * @return Path to cropped temporary PNG file, or null if cancelled / failed
   */
  public static Path show(Stage owner, File sourceFile) {
    Path[] resultHolder = new Path[1];
    Stage dialog = createDialog(owner, sourceFile, resultHolder);
    if (dialog == null) {
      return null;
    }
    dialog.showAndWait();
    return resultHolder[0];
  }

  public static Stage createDialog(Stage owner, File sourceFile, Path[] resultHolder) {
    if (sourceFile == null || !sourceFile.exists() || sourceFile.length() == 0) {
      return null;
    }

    BufferedImage sourceAwt;
    try {
      sourceAwt = ImageIO.read(sourceFile);
      if (sourceAwt == null || sourceAwt.getWidth() <= 0 || sourceAwt.getHeight() <= 0) {
        return null;
      }
    } catch (IOException ex) {
      return null;
    }

    final double srcW = sourceAwt.getWidth();
    final double srcH = sourceAwt.getHeight();
    final double cropCenterX = CANVAS_WIDTH / 2.0;
    final double cropCenterY = CANVAS_HEIGHT / 2.0;
    final double cropLeft = cropCenterX - CROP_SIZE / 2.0;
    final double cropTop = cropCenterY - CROP_SIZE / 2.0;

    // Minimum zoom: ensure the image at least covers the CROP_SIZE in both dimensions (1.0x)
    final double minZoom = Math.max(CROP_SIZE / srcW, CROP_SIZE / srcH);
    final double maxRelZoom = 4.0;

    final double[] currentRelZoom = new double[] {1.0};
    final double[] imgPos = new double[] {
      cropCenterX - (srcW * minZoom) / 2.0,
      cropCenterY - (srcH * minZoom) / 2.0
    };

    Stage dialog = new Stage();
    if (owner != null && owner.isShowing()) {
      dialog.initOwner(owner);
      dialog.initModality(Modality.APPLICATION_MODAL);
    }
    dialog.setTitle("裁切头像");
    dialog.setResizable(false);

    VBox root = new VBox(12);
    root.setPadding(new Insets(16, 20, 20, 20));
    root.setStyle("-fx-background-color: #FFFFFF;");
    root.setAlignment(Pos.TOP_CENTER);

    Label titleLbl = new Label("裁切头像 (1:1 正方形与圆形预览)");
    titleLbl.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1E293B;");

    Label tipLbl = new Label("拖动图片调整取景位置，使用鼠标滚轮或下方滑块缩放大小");
    tipLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #64748B;");

    Canvas canvas = new Canvas(CANVAS_WIDTH, CANVAS_HEIGHT);
    canvas.setId("cropperCanvas");
    canvas.setCursor(javafx.scene.Cursor.OPEN_HAND);
    canvas.setStyle("-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 8, 0, 0, 2);");

    Image fxImage = new Image(sourceFile.toURI().toString());

    Runnable clampPosition = () -> {
      double curZ = minZoom * currentRelZoom[0];
      double curW = srcW * curZ;
      double curH = srcH * curZ;

      double minX = cropLeft + CROP_SIZE - curW;
      double maxX = cropLeft;
      if (minX > maxX) {
        imgPos[0] = cropLeft + (CROP_SIZE - curW) / 2.0;
      } else {
        imgPos[0] = Math.max(minX, Math.min(maxX, imgPos[0]));
      }

      double minY = cropTop + CROP_SIZE - curH;
      double maxY = cropTop;
      if (minY > maxY) {
        imgPos[1] = cropTop + (CROP_SIZE - curH) / 2.0;
      } else {
        imgPos[1] = Math.max(minY, Math.min(maxY, imgPos[1]));
      }
    };

    Runnable redraw = () -> {
      GraphicsContext gc = canvas.getGraphicsContext2D();
      gc.clearRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

      // Dark slate background for canvas viewport
      gc.setFill(Color.web("#0F172A"));
      gc.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

      // Draw transformed image
      double curZ = minZoom * currentRelZoom[0];
      double curW = srcW * curZ;
      double curH = srcH * curZ;
      gc.drawImage(fxImage, imgPos[0], imgPos[1], curW, curH);

      // Draw dimmed vignette overlay outside the crop circle
      gc.save();
      gc.beginPath();
      gc.rect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
      gc.arc(cropCenterX, cropCenterY, CROP_SIZE / 2.0, CROP_SIZE / 2.0, 0, 360);
      gc.closePath();
      gc.setFillRule(FillRule.EVEN_ODD);
      gc.setFill(Color.rgb(15, 23, 42, 0.72));
      gc.fill();
      gc.restore();

      // Draw circular crop outline
      gc.setStroke(Color.web("#38BDF8"));
      gc.setLineWidth(2.5);
      gc.strokeOval(cropLeft, cropTop, CROP_SIZE, CROP_SIZE);

      // Draw subtle grid guides inside circle
      gc.setStroke(Color.rgb(255, 255, 255, 0.22));
      gc.setLineWidth(1.0);
      gc.strokeLine(cropLeft + CROP_SIZE / 3.0, cropTop, cropLeft + CROP_SIZE / 3.0, cropTop + CROP_SIZE);
      gc.strokeLine(cropLeft + CROP_SIZE * 2.0 / 3.0, cropTop, cropLeft + CROP_SIZE * 2.0 / 3.0, cropTop + CROP_SIZE);
      gc.strokeLine(cropLeft, cropTop + CROP_SIZE / 3.0, cropLeft + CROP_SIZE, cropTop + CROP_SIZE / 3.0);
      gc.strokeLine(cropLeft, cropTop + CROP_SIZE * 2.0 / 3.0, cropLeft + CROP_SIZE, cropTop + CROP_SIZE * 2.0 / 3.0);
    };

    // Mouse drag interaction with interactive cursor feedback
    final double[] dragStart = new double[2];
    canvas.setOnMousePressed(e -> {
      canvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
      dragStart[0] = e.getX();
      dragStart[1] = e.getY();
    });

    canvas.setOnMouseReleased(e -> {
      canvas.setCursor(javafx.scene.Cursor.OPEN_HAND);
    });

    canvas.setOnMouseDragged(e -> {
      double dx = e.getX() - dragStart[0];
      double dy = e.getY() - dragStart[1];
      dragStart[0] = e.getX();
      dragStart[1] = e.getY();
      imgPos[0] += dx;
      imgPos[1] += dy;
      clampPosition.run();
      redraw.run();
    });

    Slider zoomSlider = new Slider(1.0, maxRelZoom, 1.0);
    zoomSlider.setId("cropperZoomSlider");
    zoomSlider.setPrefWidth(180);

    Label zoomValLbl = new Label("1.0x (100%)");
    zoomValLbl.setId("cropperZoomValLbl");
    zoomValLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #0284C7; -fx-min-width: 72px;");

    zoomSlider.valueProperty().addListener((obs, oldV, newV) -> {
      double oldRel = currentRelZoom[0];
      double newRel = newV.doubleValue();
      if (Math.abs(newRel - oldRel) > 0.0001) {
        double oldZ = minZoom * oldRel;
        double newZ = minZoom * newRel;
        // Zoom relative to crop circle center
        imgPos[0] = cropCenterX - (cropCenterX - imgPos[0]) * (newZ / oldZ);
        imgPos[1] = cropCenterY - (cropCenterY - imgPos[1]) * (newZ / oldZ);
        currentRelZoom[0] = newRel;
        zoomValLbl.setText(String.format("%.1fx (%d%%)", newRel, (int) Math.round(newRel * 100)));
        clampPosition.run();
        redraw.run();
      }
    });

    canvas.setOnScroll(e -> {
      double factor = e.getDeltaY() > 0 ? 1.08 : 0.92;
      double targetRel = Math.max(1.0, Math.min(maxRelZoom, currentRelZoom[0] * factor));
      zoomSlider.setValue(targetRel);
    });

    // Zoom controls bar
    HBox zoomBox = new HBox(8);
    zoomBox.setAlignment(Pos.CENTER);
    Label zoomLbl = new Label("缩放:");
    zoomLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #475569;");
    Button resetPosBtn = new Button("居中复位");
    resetPosBtn.setId("cropperResetBtn");
    resetPosBtn.setStyle("-fx-font-size: 11px; -fx-background-color: #F1F5F9; -fx-text-fill: #475569; -fx-cursor: hand;");
    resetPosBtn.setOnAction(e -> {
      currentRelZoom[0] = 1.0;
      zoomSlider.setValue(1.0);
      zoomValLbl.setText("1.0x (100%)");
      imgPos[0] = cropCenterX - (srcW * minZoom) / 2.0;
      imgPos[1] = cropCenterY - (srcH * minZoom) / 2.0;
      clampPosition.run();
      redraw.run();
    });
    zoomBox.getChildren().addAll(zoomLbl, zoomSlider, zoomValLbl, resetPosBtn);

    // Button actions bar
    HBox actions = new HBox(12);
    actions.setAlignment(Pos.CENTER_RIGHT);
    actions.setPadding(new Insets(8, 0, 0, 0));

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    Button cancelBtn = new Button("取消");
    cancelBtn.setId("cropperCancelBtn");
    cancelBtn.setStyle("-fx-background-color: #F1F5F9; -fx-text-fill: #475569; -fx-font-size: 13px; -fx-padding: 7 18; -fx-background-radius: 6; -fx-cursor: hand;");

    Button confirmBtn = new Button("确定裁切");
    confirmBtn.setId("cropperConfirmBtn");
    confirmBtn.setStyle("-fx-background-color: #2563EB; -fx-text-fill: #FFFFFF; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 7 20; -fx-background-radius: 6; -fx-cursor: hand;");

    actions.getChildren().addAll(spacer, cancelBtn, confirmBtn);

    root.getChildren().addAll(titleLbl, tipLbl, canvas, zoomBox, actions);

    clampPosition.run();
    redraw.run();

    cancelBtn.setOnAction(e -> dialog.close());

    confirmBtn.setOnAction(e -> {
      try {
        double curZ = minZoom * currentRelZoom[0];
        double srcCropX = (cropLeft - imgPos[0]) / curZ;
        double srcCropY = (cropTop - imgPos[1]) / curZ;
        double srcCropSize = CROP_SIZE / curZ;

        BufferedImage cropped = cropImage(sourceAwt, srcCropX, srcCropY, srcCropSize, OUTPUT_SIZE);
        File tmpFile = File.createTempFile("sip_avatar_cropped_", ".png");
        tmpFile.deleteOnExit();
        ImageIO.write(cropped, "png", tmpFile);
        if (resultHolder != null && resultHolder.length > 0) {
          resultHolder[0] = tmpFile.toPath();
        }
        dialog.close();
      } catch (Exception ex) {
        dialog.close();
      }
    });

    Scene scene = new Scene(root);
    scene.setOnKeyPressed(ke -> {
      switch (ke.getCode()) {
        case ESCAPE -> cancelBtn.fire();
        case ENTER -> confirmBtn.fire();
        case LEFT -> {
          imgPos[0] -= 6;
          clampPosition.run();
          redraw.run();
        }
        case RIGHT -> {
          imgPos[0] += 6;
          clampPosition.run();
          redraw.run();
        }
        case UP -> {
          imgPos[1] -= 6;
          clampPosition.run();
          redraw.run();
        }
        case DOWN -> {
          imgPos[1] += 6;
          clampPosition.run();
          redraw.run();
        }
        case PLUS, EQUALS -> {
          zoomSlider.setValue(Math.min(maxRelZoom, currentRelZoom[0] + 0.15));
        }
        case MINUS -> {
          zoomSlider.setValue(Math.max(1.0, currentRelZoom[0] - 0.15));
        }
        default -> {}
      }
    });
    dialog.setScene(scene);
    return dialog;
  }

  /**
   * Pure functional image cropping utility method.
   *
   * @param src Source BufferedImage
   * @param srcCropX Left X in source coordinates
   * @param srcCropY Top Y in source coordinates
   * @param srcCropSize Width and height of square crop in source coordinates
   * @param targetSize Final output square width and height (e.g. 400)
   * @return Perfectly cropped 1:1 square BufferedImage
   */
  public static BufferedImage cropImage(
      BufferedImage src,
      double srcCropX,
      double srcCropY,
      double srcCropSize,
      int targetSize) {
    if (src == null) {
      throw new IllegalArgumentException("Source image cannot be null");
    }

    BufferedImage out = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = out.createGraphics();

    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

    int sx1 = (int) Math.round(srcCropX);
    int sy1 = (int) Math.round(srcCropY);
    int sx2 = (int) Math.round(srcCropX + srcCropSize);
    int sy2 = (int) Math.round(srcCropY + srcCropSize);

    // Draw region [sx1, sy1, sx2, sy2] from src into [0, 0, targetSize, targetSize]
    g2.drawImage(src, 0, 0, targetSize, targetSize, sx1, sy1, sx2, sy2, null);
    g2.dispose();

    return out;
  }
}
