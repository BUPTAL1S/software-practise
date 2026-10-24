package lab.sip.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.Pane;
import javafx.scene.control.TableCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 会议房间的入会按钮不能「点了没反应」。
 * 之前引擎没启动/没注册时，按钮照样可点，但内部守卫直接 return，界面上没有任何反馈。
 * 现在要求：点击后必须给出原因，并且不能真的发起呼叫。
 */
class RoomJoinFeedbackTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
    }
    Platform.setImplicitExit(false);
  }

  @Test
  void joinWithoutEngineExplainsWhyInsteadOfDoingNothing() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<String> lines = new ArrayList<>();

    Platform.runLater(
        () -> {
          try {
            Stage stage = new Stage();
            MainApp app = new MainApp();
            app.start(stage);
            app.getRoomRecords().clear();
            app.getRoomRecords().add(new HttpFileClient.RoomInfo(101L, "room-101", "sip:room-101@lab", 8, 3));
            app.getRightTabPane().getSelectionModel().select(app.getRoomTab());
            stage.show();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            app.getRoomTableView().refresh();
            stage.getScene().getRoot().layout();

            assertFalse(app.getSip().isStarted(), "this test assumes the engine is not started yet");

            List<Button> buttons = actionButtons(app);
            assertNotNull(buttons, "room row must render action buttons");
            assertTrue(buttons.size() >= 3, "expected join/video/audio buttons, got " + buttons.size());

            // 未启动引擎时按钮仍然可点（禁用等于没有反馈），但点击必须给出原因。
            Button joinMain = buttons.get(0);
            assertFalse(joinMain.isDisable(), "未启动引擎时【加入会议】不应禁用，否则用户点不动也看不到原因");
            joinMain.fire();

            assertFalse(app.getSip().isInCall(), "未启动引擎时不允许真的发起呼叫");
            String hint = app.getRoomHintLabel().getText();
            assertNotNull(hint);
            assertTrue(
                hint.contains("引擎还没启动"),
                "点击后提示行必须说明引擎没启动，实际: " + hint);
            lines.add("加入会议 -> " + hint);
            System.out.println("ROOM_JOIN_FEEDBACK: " + hint);

            // 视频按钮同理：不能静默失败，要提示改用语音或先启动引擎。
            Button joinVideo = buttons.get(1);
            assertFalse(joinVideo.isDisable(), "未启动引擎时【视频】不应禁用");
            joinVideo.fire();
            assertFalse(app.getSip().isInCall(), "未启动引擎时不允许真的发起视频呼叫");
            String videoHint = app.getRoomHintLabel().getText();
            assertTrue(
                videoHint.contains("引擎还没启动"),
                "点击【视频】后必须说明原因，实际: " + videoHint);
            lines.add("视频 -> " + videoHint);

            Button joinAudio = buttons.get(2);
            assertFalse(joinAudio.isDisable(), "未启动引擎时【语音】不应禁用");
            joinAudio.fire();
            assertFalse(app.getSip().isInCall(), "未启动引擎时不允许真的发起语音呼叫");
            String audioHint = app.getRoomHintLabel().getText();
            assertTrue(
                audioHint.contains("引擎还没启动"),
                "点击【语音】后必须说明原因，实际: " + audioHint);
            lines.add("语音 -> " + audioHint);

            latch.countDown();
          } catch (Throwable t) {
            failure.set(t);
            latch.countDown();
          }
        });

    assertTrue(latch.await(25, TimeUnit.SECONDS), "room join feedback check timed out");
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
  }

  private static List<Button> actionButtons(MainApp app) {
    for (Node n : app.getRoomTableView().lookupAll(".table-cell")) {
      if (!(n instanceof TableCell)) {
        continue;
      }
      TableCell<?, ?> cell = (TableCell<?, ?>) n;
      if (cell.getTableColumn() == null
          || !"操作".equals(cell.getTableColumn().getText())
          || cell.getIndex() != 0
          || !(cell.getGraphic() instanceof Pane)) {
        continue;
      }
      List<Button> out = new ArrayList<>();
      for (Node child : ((Pane) cell.getGraphic()).getChildren()) {
        if (child instanceof Button) {
          out.add((Button) child);
        }
      }
      return out;
    }
    return null;
  }
}
