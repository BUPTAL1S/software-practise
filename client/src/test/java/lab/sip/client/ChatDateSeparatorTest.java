package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ChatDateSeparatorTest {
  @Test void datesFollowLocalMidnightHistoryChangesAndConversationSwitches() throws Exception {
    try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
    Platform.setImplicitExit(false);
    CompletableFuture<Void> done = new CompletableFuture<>();
    Platform.runLater(() -> {
      Stage stage = new Stage();
      MainApp app = new MainApp();
      try {
        ListView<ChatMessage> list = new ListView<>(FXCollections.observableArrayList(
            message("2025-12-31T23:59:59", false, "跨年前的消息"),
            message("2026-01-01T00:00:00", true, "跨年后的消息"),
            message("2026-01-01T09:30:00", false, "同一天的消息")));
        list.setCellFactory(view -> app.new ChatMessageCell());
        list.getStyleClass().add("chat-list-view");
        Scene scene = new Scene(list, 720, 640);
        scene.getStylesheets().add(getClass().getResource("/modern-theme.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        layout(list);
        assertDate(list, 0, "2025年12月31日");
        assertDate(list, 1, "2026年1月1日");
        assertDate(list, 2, null);

        list.getItems().add(0, message("2025-12-31T12:00:00", true, "更早的历史消息"));
        layout(list);
        assertDate(list, 0, "2025年12月31日");
        assertDate(list, 1, null);
        assertDate(list, 2, "2026年1月1日");
        list.getItems().remove(0);
        layout(list);
        assertDate(list, 0, "2025年12月31日");

        // Switching conversations must recompute recycled cells, including file messages.
        list.setItems(FXCollections.observableArrayList(
            message("2026-10-30T16:42:06", false, "明天把会议资料发你。"),
            message("2026-10-30T17:43:03", true, "好的，收到。"),
            message("2026-10-31T14:56:54", false, "今天的会议资料准备好了。"),
            message("2026-10-31T15:01:08", true,
                new FileMessage("会议资料.pdf", 2048, "/api/files/test").toWireFormat())));
        layout(list);
        assertDate(list, 0, "2026年10月30日");
        assertDate(list, 1, null);
        assertDate(list, 2, "2026年10月31日");
        assertDate(list, 3, null);
        Label date = (Label) cell(list, 2).getGraphic().lookup(".chat-date-label");
        var bounds = date.localToScene(date.getBoundsInLocal());
        assertTrue(Math.abs((bounds.getMinX() + bounds.getMaxX()) / 2 - scene.getWidth() / 2) < 20,
            "Date should be centered across the conversation, independent of bubble direction");
        var snapshot = scene.snapshot(null);
        BufferedImage image = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
          image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
        ImageIO.write(image, "png", new File("target/chat-dates-preview.png"));

        list.getItems().clear();
        list.getItems().add(message("2026-11-01T00:00:00", true, "新的月份"));
        layout(list);
        assertDate(list, 0, "2026年11月1日");
        done.complete(null);
      } catch (Throwable error) { done.completeExceptionally(error); }
      finally { stage.close(); app.getSipService().shutdown(); }
    });
    done.get(20, TimeUnit.SECONDS);
  }

  private static ChatMessage message(String localTime, boolean outgoing, String text) {
    return ChatMessage.fromServer(outgoing ? "1001" : "1002", outgoing ? "1002" : "1001", outgoing,
        text, LocalDateTime.parse(localTime).atZone(ZoneId.systemDefault()).toInstant(), null, outgoing ? "已送达" : "");
  }

  private static void layout(ListView<ChatMessage> list) {
    list.applyCss();
    list.layout();
  }

  private static ListCell<?> cell(ListView<ChatMessage> list, int index) {
    return list.lookupAll(".list-cell").stream().filter(node -> node instanceof ListCell<?> cell
        && !cell.isEmpty() && cell.getIndex() == index).map(node -> (ListCell<?>) node).findFirst().orElseThrow();
  }

  private static void assertDate(ListView<ChatMessage> list, int index, String expected) {
    VBox row = (VBox) cell(list, index).getGraphic();
    var divider = row.lookup(".chat-date-divider");
    assertEquals(expected != null, divider.isVisible(), "Date visibility at index " + index);
    assertEquals(expected != null, divider.isManaged(), "Hidden dates must not leave gaps");
    if (expected != null) assertEquals(expected, ((Label) divider.lookup(".chat-date-label")).getText());
    assertTrue(row.lookupAll(".label").stream().filter(node -> node instanceof Label)
        .map(node -> ((Label) node).getText()).anyMatch(text -> text.contains(list.getItems().get(index).formatTime())),
        "The message's existing time must remain visible");
  }
}
