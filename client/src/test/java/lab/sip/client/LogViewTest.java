package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LogViewTest {

  @BeforeAll
  static void initJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
    }
    Platform.setImplicitExit(false);
  }

  @Test
  void testLogViewDetachableAndDocking() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean detachSuccess = new AtomicBoolean(false);
    AtomicBoolean dockBackSuccess = new AtomicBoolean(false);

    Platform.runLater(
        () -> {
          try {
            LogView logs = new LogView();
            StackPane host = new StackPane(logs);
            logs.setupDockHost(host);

            assertFalse(logs.isDetached(), "Initially LogView should not be detached");
            assertEquals(1, host.getChildren().size());
            assertSame(logs, host.getChildren().get(0));

            // Populate some logs
            logs.appendApp("系统初始化中...");
            logs.append("注册", "REGISTER 正在发送");
            logs.appendSip(true, "REGISTER sip:1001@lab SIP/2.0", "REGISTER ... raw SIP");
            logs.appendSip(false, "SIP/2.0 200 OK", "SIP/2.0 200 OK ... raw SIP");

            // Test detachment
            logs.detachToWindow();
            if (logs.isDetached() && host.getChildren().size() == 1 && host.getChildren().get(0) != logs) {
              detachSuccess.set(true);
            }

            // Test dockBack
            logs.dockBack();
            if (!logs.isDetached() && host.getChildren().size() == 1 && host.getChildren().get(0) == logs) {
              dockBackSuccess.set(true);
            }
          } catch (Exception ex) {
            ex.printStackTrace();
          } finally {
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "JavaFX thread timed out");
    assertTrue(detachSuccess.get(), "LogView detachToWindow should succeed and replace host with placeholder");
    assertTrue(dockBackSuccess.get(), "LogView dockBack should restore LogView into host container");
  }

  @Test
  void testSecondarySidebarWidthIsPermanentlyLocked() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean verified = new AtomicBoolean(false);

    Platform.runLater(
        () -> {
          try {
            MainViewAssembler assembler = new MainViewAssembler();
            TabPane tabPane = new TabPane();
            Tab tab0 = new Tab("消息与文件", new VBox());
            Tab tab1 = new Tab("会议房间", new VBox());
            Tab tab2 = new Tab("统计", new VBox());
            LogView logs = new LogView();
            StackPane consoleHost = new StackPane(logs);
            logs.setupDockHost(consoleHost);
            Tab tab3 = new Tab("信令与控制台", consoleHost);
            tabPane.getTabs().addAll(tab0, tab1, tab2, tab3);

            BorderPane root =
                assembler.assembleProductUi(
                    new Stage(),
                    new Label("就绪"),
                    new Label("H.264"),
                    new Label("在线"),
                    new TextField("1002"),
                    new Button[] {new Button("1001"), new Button("1002"), new Button("1003"), new Button("room-101")},
                    new Button("视频"),
                    new Button("语音"),
                    new Button("挂断"),
                    new Button("预览"),
                    new HBox(),
                    new TextField("127.0.0.1"),
                    new Button("填入本机IP"),
                    new TextField("http://localhost:8080"),
                    new Button("测试"),
                    new javafx.scene.control.ComboBox<>(),
                    new TextField("lab"),
                    new TextField("1001"),
                    new PasswordField(),
                    new TextField("5062"),
                    new Button("启动"),
                    new Button("注册"),
                    new Button("登录"),
                    logs,
                    tabPane);

            // Verify root layout structure: Left is nav rail, Center is centerSplit (HBox of sidebar and workspace)
            assertNotNull(root.getLeft(), "Navigation rail should exist on the left");
            assertTrue(root.getCenter() instanceof HBox, "Center should be HBox centerSplit");

            HBox centerSplit = (HBox) root.getCenter();
            assertEquals(2, centerSplit.getChildren().size(), "centerSplit contains sidebar and workspace");

            VBox secondarySidebar = (VBox) centerSplit.getChildren().get(0);
            BorderPane workspace = (BorderPane) centerSplit.getChildren().get(1);

            // Verify secondary sidebar width lock
            assertEquals(260.0, secondarySidebar.getMinWidth(), 0.01, "Sidebar min width must be exactly 260px");
            assertEquals(260.0, secondarySidebar.getMaxWidth(), 0.01, "Sidebar max width must be exactly 260px");
            assertEquals(260.0, secondarySidebar.getPrefWidth(), 0.01, "Sidebar pref width must be exactly 260px");

            // Verify workspace bottom drawer is null (no drawer squashing chat messages)
            assertNull(workspace.getBottom(), "Workspace bottom drawer must be null - signaling console is now in Tab 3");

            verified.set(true);
          } catch (Exception ex) {
            ex.printStackTrace();
          } finally {
            latch.countDown();
          }
        });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "JavaFX thread timed out");
    assertTrue(verified.get(), "Sidebar width and workspace layout verified successfully");
  }
}
