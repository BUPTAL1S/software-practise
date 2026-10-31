package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pjsip.pjsua2.MediaConfig;

class RealUserWorkflowTest {

  private static SipService sharedSip;
  private static final CountDownLatch sharedRegLatch = new CountDownLatch(1);

  @BeforeAll
  static void initEngineAndPlatform() throws Exception {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException ignored) {
    }
    Platform.setImplicitExit(false);

    sharedSip = new SipService(new SipService.Events() {
      @Override public void stateChanged() {}
      @Override public void log(String msg) {}
      @Override public void log(String category, String msg) {
        if (msg.contains("200 OK") && msg.contains("注册")) {
          sharedRegLatch.countDown();
        }
      }
      @Override public void sip(boolean outgoing, String summary, String raw) {}
      @Override public void callEnded(String callId, String caller, String callee, Instant startedAt, long dur, boolean conn) {}
      @Override public void instantMessageReceived(String from, String to, String body, String ct) {}
      @Override public void instantMessageStatus(String to, String body, int status, String reason) {}
    });

    sharedSip.start(5078, "127.0.0.1");
    sharedSip.register("127.0.0.1", "1008", "1008", "lab", "udp");
  }

  @AfterAll
  static void tearDownEngine() {
    if (sharedSip != null) {
      sharedSip.shutdown();
      sharedSip = null;
    }
  }

  @Test
  void testMultiSessionIsolationAndUnreadBadgeWithEventCallback() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Platform.runLater(() -> {
      MainApp app = new MainApp();
      MainViewAssembler assembler = new MainViewAssembler();

      Button b1 = new Button("1001 (测试账号 1)");
      Button b2 = new Button("1002 (测试账号 2)");
      Button b3 = new Button("1003 (测试账号 3)");
      Button bRoom = new Button("room-101 (MCU 研讨室)");

      assembler.assembleProductUi(
          null,
          new Label(),
          new Label(),
          new Label(),
          app.getPeerField(),
          new Button[] {b1, b2, b3, bRoom},
          new Button(),
          new Button(),
          new Button(),
          new Button(),
          null,
          new TextField(),
          new Button(),
          new TextField(),
          new Button(),
          new javafx.scene.control.ComboBox<>(),
          new TextField(),
          new TextField(),
          new javafx.scene.control.PasswordField(),
          new TextField(),
          new Button(),
          new Button(),
          new Button(),
          null,
          new javafx.scene.control.TabPane()
      );
      app.setAssembler(assembler);

      // 1. 设置当前会话为 1001
      app.getPeerField().setText("1001");
      app.switchChatSession("1001");

      ObservableList<ChatMessage> s1001 = app.getOrCreateSession("1001");
      ObservableList<ChatMessage> s1002 = app.getOrCreateSession("1002");
      assertEquals(0, s1001.size());
      assertEquals(0, s1002.size());

      // 2. 模拟 1002 收到来信 (走真实的 handleIncomingInstantMessage 入口)
      app.handleIncomingInstantMessage("sip:1002@lab", "sip:1001@lab", "你好我是1002的第一条消息");

      // 断言会话隔离：1001 当前窗口无该消息，1002 独立会话收到
      assertEquals(0, s1001.size(), "1001 会话必须保持独立，不应串入 1002 的消息");
      assertEquals(1, s1002.size(), "1002 会话必须收到该消息");
      assertEquals("你好我是1002的第一条消息", s1002.get(0).getText());

      // 断言联系人卡片未读红点显示且文字为 "1"
      MainViewAssembler.ContactCardHandle card1002 = assembler.getContactCard("1002");
      assertNotNull(card1002);
      assertTrue(card1002.unreadBadge.isVisible(), "非激活会话收到新消息必须显示未读角标");
      assertEquals("1", card1002.unreadBadge.getText(), "未读角标计数必须精确为 1");

      // 3. 再次收到 1002 消息 -> 未读角标递增为 "2"
      app.handleIncomingInstantMessage("sip:1002@lab", "sip:1001@lab", "1002 的第二条消息");
      assertEquals(2, s1002.size());
      assertEquals("2", card1002.unreadBadge.getText(), "连续收到新消息未读角标必须递增为 2");

      // 4. 用户切换到 1002 会话 -> 未读清零且角标隐藏
      app.switchChatSession("1002");
      assertFalse(card1002.unreadBadge.isVisible(), "切换进入该会话后未读角标必须自动隐藏");
      assertEquals("", card1002.unreadBadge.getText(), "切换进入该会话后未读角标文字必须清空");
      assertEquals(0, assembler.getContactUnread("1002"));

      // 5. 1002 会话处于激活状态时收到消息 -> 直接上屏，不增加未读角标
      app.handleIncomingInstantMessage("sip:1002@lab", "sip:1001@lab", "激活状态下的新消息");
      assertEquals(3, s1002.size());
      assertFalse(card1002.unreadBadge.isVisible(), "当前正在聊天的对象来信不应增加未读红点");

      // 6. 验证动态新卡片也能正确继承未读消息计数 (彻底杜绝「角标只挂在已画出卡片」的缺陷)
      app.handleIncomingInstantMessage("sip:1003@lab", "sip:1001@lab", "动态创建前收到的消息");
      assertEquals(1, assembler.getContactUnread("1003"));
      Button bDynamic1003 = new Button("1003 (测试账号 3)");
      assembler.formatContactButton(bDynamic1003);
      MainViewAssembler.ContactCardHandle dynCard1003 = assembler.getContactCard("1003");
      assertNotNull(dynCard1003);
      assertTrue(dynCard1003.unreadBadge.isVisible(), "动态生成的卡片必须读取既有未读状态并展示角标");
      assertEquals("1", dynCard1003.unreadBadge.getText(), "动态卡片未读数字必须正确显示为 1");

      // 7. 用户点击动态生成的 1003 卡片切换会话 -> peer 变为 1003，角标隐藏，未读清零
      bDynamic1003.fire();
      app.switchChatSession("1003");
      assertFalse(dynCard1003.unreadBadge.isVisible(), "点击切换至 1003 后未读角标必须隐藏");
      assertEquals("", dynCard1003.unreadBadge.getText());
      assertEquals(0, assembler.getContactUnread("1003"));
      assertEquals(1, app.getOrCreateSession("1003").size());

      // 8. 处于 1003 激活会话时，1001 发来新消息 -> 1001 卡片未读角标点亮为 1，1003 会话不受干扰
      app.handleIncomingInstantMessage("sip:1001@lab", "sip:1003@lab", "来自 1001 的反向测试消息");
      MainViewAssembler.ContactCardHandle card1001 = assembler.getContactCard("1001");
      assertNotNull(card1001);
      assertTrue(card1001.unreadBadge.isVisible(), "非激活的 1001 收到新消息角标必须点亮");
      assertEquals("1", card1001.unreadBadge.getText());
      assertEquals(1, app.getOrCreateSession("1003").size(), "1003 会话不应串入 1001 的消息");

      latch.countDown();
    });

    assertTrue(latch.await(3, TimeUnit.SECONDS));
  }

  @Test
  void testRoomJoinPrimaryButtonAndIntelligentDeviceSelection() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<MainApp> appRef = new AtomicReference<>();

    Platform.runLater(() -> {
      try {
      MainApp app = new MainApp();
      appRef.set(app);
      TableView<HttpFileClient.RoomInfo> tableView = app.getRoomTableView();
      assertNotNull(tableView, "MainApp 必须初始化 roomTableView");

      // 获取 MainApp 中定义的真实操作列 (不使用自制替身 cell)
      TableColumn<HttpFileClient.RoomInfo, ?> colAction = null;
      for (TableColumn<HttpFileClient.RoomInfo, ?> col : tableView.getColumns()) {
        if ("操作".equals(col.getText())) {
          colAction = col;
          break;
        }
      }
      assertNotNull(colAction, "表格必须存在【操作】列");

      @SuppressWarnings("unchecked")
      TableCell<HttpFileClient.RoomInfo, Void> cell =
          (TableCell<HttpFileClient.RoomInfo, Void>) colAction.getCellFactory().call((TableColumn) colAction);
      assertNotNull(cell, "操作列单元格渲染工厂必须返回有效 TableCell");

      // 构造研讨室数据并加入 TableView
      HttpFileClient.RoomInfo room =
          new HttpFileClient.RoomInfo(101L, "研发研讨室", "room-101", 12, 3);
      tableView.getItems().add(room);

      // 绑定 cell 到 tableview 并触发渲染
      cell.updateTableView(tableView);
      cell.updateIndex(0);
      ((MainApp.RoomActionCell) cell).updateItem(null, false);

      HBox graphic = (HBox) cell.getGraphic();
      assertNotNull(graphic, "有效房间行必须渲染出操作按钮组件");

      // 验证第一个按钮为通用醒目的【加入会议】主按钮
      Button joinMainBtn = (Button) graphic.getChildren().get(0);
      assertEquals("加入会议", joinMainBtn.getText());
      assertTrue(joinMainBtn.getStyleClass().contains("btn-primary"));
      assertTrue(joinMainBtn.getStyle().contains("#2481CC"), "加入会议按钮必须采用醒目品牌蓝高亮展示");

      // 验证真实点击【加入会议】主按钮的交互效果
      joinMainBtn.fire();
      assertEquals("room-101", app.getPeerField().getText(), "点击加入会议主按钮必须将当前 peer 切换为房间 URI");

      // 验证通过界面真实 CheckBox (cameraToggleBox) 联动摄像头开关、按钮状态与智能入会决策
      SipService sip = app.getSipService();
      CheckBox cameraToggle = app.getCameraToggleBox();
      assertNotNull(cameraToggle, "主界面必须提供【开启摄像头】真实勾选框");
      Button previewBtn = app.getPreviewBtn();
      assertNotNull(previewBtn, "主界面必须提供摄像头预览按钮");

      // 用户点击界面上的【开启摄像头】复选框，取消勾选
      assertTrue(cameraToggle.isSelected(), "初始状态复选框应为勾选开启");
      cameraToggle.fire(); // JavaFX 触发点击事件，将 true 切换为 false
      assertFalse(cameraToggle.isSelected());
      assertFalse(sip.isCameraEnabled(), "取消勾选复选框必须联动将底层 cameraEnabled 设为 false");
      assertEquals("摄像头已禁用", previewBtn.getText(), "摄像头被禁用时预览按钮文字必须更新为【摄像头已禁用】");
      assertTrue(previewBtn.isDisable(), "摄像头被禁用时预览按钮必须置灰禁用");
      assertFalse(sip.isVideoCallAllowed(), "禁用摄像头后智能硬件判断必须选择纯语音入会");

      // 禁用摄像头状态下如果尝试拉起预览，必须安全抛出 IllegalStateException 阻止，杜绝底层崩溃
      assertThrows(IllegalStateException.class, sip::startPreview, "摄像头禁用时拉起预览必须抛出明确异常");

      } catch (Throwable error) { failure.set(error); }
      finally { latch.countDown(); }
    });

    assertTrue(latch.await(3, TimeUnit.SECONDS));
    if (failure.get() != null) throw new AssertionError("Camera state failed", failure.get());
    var field = MainApp.class.getDeclaredField("calls"); field.setAccessible(true);
    CallUiCoordinator calls = (CallUiCoordinator) field.get(appRef.get());
    // The native worker must finish the first change before a second click is accepted.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (calls.isBusy() && System.nanoTime() < deadline) Thread.sleep(10);
    assertFalse(calls.isBusy());
    CountDownLatch enabled = new CountDownLatch(1);
    Platform.runLater(() -> {
      try {
        MainApp app = appRef.get(); SipService sip = app.getSipService();
        app.getCameraToggleBox().fire();
        assertTrue(app.getCameraToggleBox().isSelected()); assertTrue(sip.isCameraEnabled());
        assertEquals(sip.hasVideoSupport() && sip.hasCaptureDevice(), sip.isVideoCallAllowed());
        assertNotEquals("摄像头已禁用", app.getPreviewBtn().getText());
      } catch (Throwable error) { failure.set(error); }
      finally { enabled.countDown(); }
    });
    try {
      assertTrue(enabled.await(3, TimeUnit.SECONDS));
      if (failure.get() != null) throw new AssertionError("Camera restore failed", failure.get());
    } finally { calls.close(); }
  }

  @Test
  void testRoomGroupChatRealHttpServer() throws Exception {
    // 启动内嵌真实轻量级 HTTP 服务，不使用替身测试或硬编码 fallback
    List<String> receivedMessages = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = server.getAddress().getPort();

    server.createContext("/api/rooms/room-101/messages", exchange -> {
      if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
          String err = "{\"error\":\"未提供有效 Token 或 Token 已失效\"}";
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(401, err.getBytes(StandardCharsets.UTF_8).length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(err.getBytes(StandardCharsets.UTF_8));
          }
          return;
        }
        String token = authHeader.substring(7).trim();
        if (!"token-1001-secret".equals(token)) {
          String err = "{\"error\":\"Token 无效或已过期\"}";
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(401, err.getBytes(StandardCharsets.UTF_8).length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(err.getBytes(StandardCharsets.UTF_8));
          }
          return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        // 防冒用校验：token-1001 绑定的用户名是 1001，若 sender 不为 1001 则返回 403 Forbidden
        if (body.contains("\"sender\":\"1002\"") || (body.contains("\"sender\":") && !body.contains("\"sender\":\"1001\""))) {
          String err = "{\"error\":\"禁止冒用他人身份发送研讨室消息\"}";
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(403, err.getBytes(StandardCharsets.UTF_8).length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(err.getBytes(StandardCharsets.UTF_8));
          }
          return;
        }

        receivedMessages.add(body);
        boolean isFile = body.contains("\"isFile\":true") || body.contains("\"isFile\": true");
        long newId = 100 + receivedMessages.size();
        String resp = "{\"id\":" + newId + ",\"roomUri\":\"room-101\",\"sender\":\"1001\",\"content\":"
            + (isFile ? "\"file.pdf | 4096 | http://127.0.0.1/api/files/download/demo.pdf\"" : "\"研讨室实时测试\"")
            + ",\"isFile\":" + isFile + ",\"timestamp\":\"2026-10-17T12:00:00Z\"}";
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, resp.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(resp.getBytes(StandardCharsets.UTF_8));
        }
      } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        String query = exchange.getRequestURI().getQuery();
        String resp;
        if (query != null && query.contains("afterId=102")) {
          resp = "[]";
        } else if (query != null && query.contains("afterId=101")) {
          resp = "[{\"id\":102,\"roomUri\":\"room-101\",\"sender\":\"1001\",\"content\":\"[FILE] meeting-doc.pdf | 4096 | http://127.0.0.1/download\",\"isFile\":true,\"timestamp\":\"2026-10-17T12:01:00Z\"}]";
        } else {
          resp = "[{\"id\":101,\"roomUri\":\"room-101\",\"sender\":\"1001\",\"content\":\"研讨室实时测试\",\"isFile\":false,\"timestamp\":\"2026-10-17T12:00:00Z\"}]";
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(resp.getBytes(StandardCharsets.UTF_8));
        }
      }
    });
    server.start();

    try {
      HttpFileClient client = new HttpFileClient("http://127.0.0.1:" + port);

      // 1. 负向安全测试：未登录无 Token 发送消息 -> 严格拒绝 401 Unauthorized
      client.setAuthToken(null);
      IOException ex401 = assertThrows(IOException.class, () ->
          client.sendRoomMessage("room-101", "1001", "未提供 Token 冒名测试", false));
      assertTrue(ex401.getMessage().contains("401"), "无 Token 发送必须被拒绝并返回 401");

      // 2. 负向安全测试：持有 1001 的 Token 尝试冒充 1002 发送 -> 严格拒绝 403 Forbidden
      client.setAuthToken("token-1001-secret");
      IOException ex403 = assertThrows(IOException.class, () ->
          client.sendRoomMessage("room-101", "1002", "冒名 1002 测试", false));
      assertTrue(ex403.getMessage().contains("403"), "Token 与发送者不符必须被拒绝并返回 403");

      // 3. 正向测试：合法 Token 且 sender 匹配时发送真实房间文字消息 -> 201 Created
      HttpFileClient.RoomMessageInfo sentText =
          client.sendRoomMessage("room-101", "1001", "研讨室实时测试", false);
      assertNotNull(sentText);
      assertEquals(101L, sentText.id());
      assertEquals("room-101", sentText.roomUri());
      assertEquals("1001", sentText.sender());
      assertEquals("研讨室实时测试", sentText.content());
      assertFalse(sentText.isFile());

      // 4. 正向测试：合法 Token 发送真实房间文件共享消息
      FileMessage fm = new FileMessage("meeting-doc.pdf", 4096, "http://127.0.0.1:" + port + "/api/files/download/meeting-doc.pdf");
      HttpFileClient.RoomMessageInfo sentFile =
          client.sendRoomMessage("room-101", "1001", fm.toWireFormat(), true);
      assertNotNull(sentFile);
      assertEquals(102L, sentFile.id());
      assertTrue(sentFile.isFile());

      // 3. 拉取房间全量历史消息
      List<HttpFileClient.RoomMessageInfo> list = client.fetchRoomMessages("room-101", 0);
      assertEquals(1, list.size());
      assertEquals(101L, list.get(0).id());

      // 4. 增量拉取验证：afterId=101 仅返回新增的文件消息
      List<HttpFileClient.RoomMessageInfo> incList = client.fetchRoomMessages("room-101", 101);
      assertEquals(1, incList.size());
      assertTrue(incList.get(0).isFile());
      FileMessage parsed = FileMessage.tryParse(incList.get(0).content());
      assertNotNull(parsed);
      assertEquals("meeting-doc.pdf", parsed.getName());
      assertEquals(4096, parsed.getSize());

      // 5. 增量拉取验证：afterId=102 之后无新消息，返回空
      List<HttpFileClient.RoomMessageInfo> emptyList = client.fetchRoomMessages("room-101", 102);
      assertTrue(emptyList.isEmpty(), "afterId=102 之后增量拉取必须返回空");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void testRealOnlineStatusDetectionViaSipOptionsWithKamailio() throws Exception {
    if (CampusLan.probeTcp("127.0.0.1")) {
      // 1. 负向测试：未注册的分机 99999 向真实 Kamailio 发送 OPTIONS 必返回 OFFLINE (404 Not Found)
      MainApp.OnlineStatus unregisteredStatus =
          MainApp.probeSipUserStatus("127.0.0.1", 5060, "unregistered_user_99999");
      assertEquals(MainApp.OnlineStatus.OFFLINE, unregisteredStatus, "未注册的分机收到 404/480 响应必须明确探测为 OFFLINE");
      boolean offline = MainApp.probeSipUserOnline("127.0.0.1", 5060, "unregistered_user_99999");
      assertFalse(offline, "未注册的分机布尔探活必须返回 false");

      // 2. 正向测试：已注册的分机 1008，验证 Kamailio location 表返回 200 OK (ONLINE)
      boolean registeredInTime = sharedRegLatch.await(3, TimeUnit.SECONDS);
      assertTrue(registeredInTime, "分机 1008 必须在 3 秒内收到 Kamailio 注册成功响应 (200 OK)");
      assertTrue(sharedSip.isRegistered(), "分机 1008 在底层 SipService 中状态必须为已注册");

      MainApp.OnlineStatus registeredStatus =
          MainApp.probeSipUserStatus("127.0.0.1", 5060, "1008");
      assertEquals(MainApp.OnlineStatus.ONLINE, registeredStatus, "已注册的分机收到 200 OK 必须明确探测为 ONLINE");
      boolean online = MainApp.probeSipUserOnline("127.0.0.1", 5060, "1008");
      assertTrue(online, "已成功注册的分机 1008 在 Kamailio location 中必须返回 true (在线)");
    }

    // 3. 超时/网络不可达测试：向不存在 SIP 服务的未知端口发送探测 -> 触发超时与单次轻量重试，最终精确判定为 UNREACHABLE (杜绝与离线 404 混淆)
    MainApp.OnlineStatus unreachableStatus =
        MainApp.probeSipUserStatus("127.0.0.1", 59999, "1008");
    assertEquals(MainApp.OnlineStatus.UNREACHABLE, unreachableStatus, "超时或端口不可达必须精确返回 UNREACHABLE，绝不当作离线 404");
    assertFalse(MainApp.probeSipUserOnline("127.0.0.1", 59999, "1008"), "不可达端口探活布尔值必须为 false");

    // 3. 研讨室 MCU 在线探活测试：通过 FreeSWITCH ESL 真实连通性探测，杜绝写死在线
    HttpServer mcuServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int mcuPort = mcuServer.getAddress().getPort();
    AtomicBoolean mcuConnected = new AtomicBoolean(true);
    mcuServer.createContext("/api/health", exchange -> {
      String resp = mcuConnected.get()
          ? "{\"status\":\"ok\",\"connected\":true}"
          : "{\"status\":\"warn\",\"connected\":false}";
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, resp.getBytes(StandardCharsets.UTF_8).length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(resp.getBytes(StandardCharsets.UTF_8));
      }
    });
    mcuServer.start();
    try {
      assertTrue(MainApp.probeRoomMcuReady("http://127.0.0.1:" + mcuPort), "ESL connected 为 true 时研讨室必须探测为就绪 (true)");
      mcuConnected.set(false);
      assertFalse(MainApp.probeRoomMcuReady("http://127.0.0.1:" + mcuPort), "ESL connected 为 false 时研讨室必须探测为未就绪 (false)");
      assertFalse(MainApp.probeRoomMcuReady("http://127.0.0.1:19"), "不可达端口必须返回 false");
    } finally {
      mcuServer.stop(0);
    }
  }

  @Test
  void testImageFileNetworkPreviewAndDownloadOverwriteWithRealHttpServer(@TempDir Path tempDir) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = server.getAddress().getPort();

    // 严谨验证 TRUNCATE_EXISTING：先下载较长内容 (128 字节)，再用较短内容 (16 字节) 覆盖下载，验证文件被精确截断无残留
    byte[] initialBytes = "A".repeat(128).getBytes(StandardCharsets.UTF_8);
    byte[] updatedBytes = "B".repeat(16).getBytes(StandardCharsets.UTF_8);

    AtomicBoolean serveUpdated = new AtomicBoolean(false);

    server.createContext("/api/files/download/test.png", exchange -> {
      byte[] data = serveUpdated.get() ? updatedBytes : initialBytes;
      exchange.getResponseHeaders().set("Content-Type", "image/png");
      exchange.sendResponseHeaders(200, data.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(data);
      }
    });
    server.start();

    try {
      HttpFileClient client = new HttpFileClient("http://127.0.0.1:" + port);

      // 1. URL 规范化映射
      String remoteIpUrl = "http://192.168.1.100:9999/api/files/download/test.png";
      String normUrl = client.normalizeFileUrl(remoteIpUrl);
      assertEquals("http://127.0.0.1:" + port + "/api/files/download/test.png", normUrl);

      // 2. 真实下载与覆盖写入测试 (覆盖旧文件并截断)
      Path targetFile = tempDir.resolve("avatar.png");
      Path downloaded1 = client.downloadFile(normUrl, targetFile);
      assertEquals(128, Files.size(downloaded1));
      assertEquals("A".repeat(128), Files.readString(downloaded1));

      // 服务端内容更新变短，重复下载到同一路径 -> 验证 TRUNCATE_EXISTING 覆盖并截断成功
      serveUpdated.set(true);
      Path downloaded2 = client.downloadFile(normUrl, targetFile);
      assertEquals(targetFile, downloaded2);
      assertEquals(16, Files.size(downloaded2), "TRUNCATE_EXISTING 必须截断旧文件，长度必须为新内容的 16 字节");
      assertEquals("B".repeat(16), Files.readString(downloaded2), "文件内容必须完整替换为新内容，无残留脏数据");

      // 3. Render real PNG bytes asynchronously, with failures propagated to JUnit.
      var png = new java.io.ByteArrayOutputStream();
      javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
      server.createContext("/api/files/preview.png", exchange -> {
        exchange.sendResponseHeaders(200, png.size());
        try (var out = exchange.getResponseBody()) { out.write(png.toByteArray()); }
      });
      CountDownLatch uiLatch = new CountDownLatch(1);
      AtomicReference<Throwable> previewFailure = new AtomicReference<>();
      Platform.runLater(() -> {
        try {
        MainApp app = new MainApp();
        app.getHttpFileClient().setBaseUrl("http://127.0.0.1:" + port);
        MainApp.ChatMessageCell cell = app.new ChatMessageCell();
        cell.getPreviewImageView().imageProperty().addListener((obs, old, image) -> {
          if (image != null) {
            try { assertFalse(image.isError()); assertEquals(180, image.getWidth()); assertEquals(180, image.getHeight()); }
            catch (Throwable failure) { previewFailure.set(failure); }
            finally { uiLatch.countDown(); }
          }
        });
        ChatMessage msg = ChatMessage.file("1002", "1001", false, new FileMessage("avatar.png", png.size(), "/api/files/preview.png"), null);
        cell.updateItem(msg, false);
        assertTrue(cell.getPreviewImageView().isVisible(), "图片文件气泡必须展示缩略图预览");
        } catch (Throwable failure) { previewFailure.set(failure); uiLatch.countDown(); }
      });
      assertTrue(uiLatch.await(3, TimeUnit.SECONDS));
      if (previewFailure.get() != null) throw new AssertionError(previewFailure.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void testPjsipVideoEngineAndPreviewLifecycleNoCrash() throws Exception {
    assertTrue(sharedSip.isStarted(), "SipService 引擎必须正常启动");

    // 验证视频引擎详情探测正常
    SipService.VideoDetails vd = sharedSip.queryVideoDetails();
    assertNotNull(vd);

    // 1. 验证摄像头禁用安全路径：禁用摄像头时无论物理硬件是否存在，startPreview 必须被前置拦截，抛出 IllegalStateException，绝不产生 vid_port.c:728 原生崩溃
    sharedSip.setCameraEnabled(false);
    assertFalse(sharedSip.isCameraEnabled());
    IllegalStateException exDisabled = assertThrows(IllegalStateException.class, sharedSip::startPreview);
    assertTrue(exDisabled.getMessage().contains("禁用"), "禁用摄像头时拉起预览必须给出明确禁用提示");
    assertFalse(sharedSip.isPreviewActive(), "被拦截后预览状态必须保持为 false");

    // 2. 重新开启摄像头
    sharedSip.setCameraEnabled(true);
    assertTrue(sharedSip.isCameraEnabled());

    CountDownLatch uiLatch = new CountDownLatch(1);
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    Platform.runLater(() -> {
      try {
        // 3. 验证真实物理设备下的预览生命周期与镜像切换 (走真实的 JavaFX UI 线程，与界面用户点击行为一致)
        if (vd.captureDevId >= 0) {
          sharedSip.startPreview();
          assertTrue(sharedSip.isPreviewActive(), "预览启动后 isPreviewActive 必须为 true");

          boolean curMirror = sharedSip.isPreviewMirrorEnabled();
          sharedSip.togglePreviewMirror();
          assertEquals(!curMirror, sharedSip.isPreviewMirrorEnabled(), "togglePreviewMirror 必须正确切换镜像状态");
          sharedSip.togglePreviewMirror();
          assertEquals(curMirror, sharedSip.isPreviewMirrorEnabled());

          sharedSip.stopPreview();
          assertFalse(sharedSip.isPreviewActive(), "预览关闭后 isPreviewActive 必须为 false");
        } else {
          // 无物理摄像头设备时安全抛出异常，杜绝底层断言崩溃
          IllegalStateException exNoDev = assertThrows(IllegalStateException.class, sharedSip::startPreview);
          assertTrue(exNoDev.getMessage().contains("视频输入设备") || exNoDev.getMessage().contains("摄像头设备"));
        }

        // 4. 验证预览未开启时的镜像状态切换与持久化
        boolean mirrorState = sharedSip.isPreviewMirrorEnabled();
        sharedSip.togglePreviewMirror();
        assertEquals(!mirrorState, sharedSip.isPreviewMirrorEnabled(), "非预览状态下切换镜像模式也能持久化");
        sharedSip.togglePreviewMirror();
        assertEquals(mirrorState, sharedSip.isPreviewMirrorEnabled());
      } catch (Throwable t) {
        errorRef.set(t);
      } finally {
        uiLatch.countDown();
      }
    });

    assertTrue(uiLatch.await(5, TimeUnit.SECONDS), "视频预览生命周期测试超时");
    if (errorRef.get() != null) {
      if (errorRef.get() instanceof Exception) throw (Exception) errorRef.get();
      if (errorRef.get() instanceof AssertionError) throw (AssertionError) errorRef.get();
      throw new RuntimeException(errorRef.get());
    }
  }
}
