package lab.sip.client;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public final class MainApp extends Application {
  private final ObservableList<ChatMessage> chatMessages = FXCollections.observableArrayList();
  private final HttpFileClient httpFileClient = new HttpFileClient("http://localhost:8080");

  private final SipService sip =
      new SipService(
          new SipService.Events() {
            @Override
            public void log(String msg) {
              Platform.runLater(() -> logs.appendApp(msg));
            }

            @Override
            public void log(String category, String msg) {
              Platform.runLater(() -> logs.append(category, msg));
            }

            @Override
            public void sip(boolean outgoing, String summary, String raw) {
              Platform.runLater(() -> logs.appendSip(outgoing, summary, raw));
            }

            @Override
            public void stateChanged() {
              Platform.runLater(
                  () -> {
                    refreshButtons();
                    if (sip.isRegistered()) {
                      if ("tcp".equalsIgnoreCase(transport.getValue())) {
                        LocalPrefs.save(server.getText(), "tcp", httpServerField.getText());
                      }
                      if (httpFileClient.getAuthToken() == null) {
                        performLogin(true);
                      }
                    }
                  });
            }

            @Override
            public void callEnded(
                String callId,
                String caller,
                String calleeOrRoom,
                Instant startedAt,
                long durationSec,
                boolean wasConnected) {
              Platform.runLater(
                  () -> {
                    logs.append(
                        "通话",
                        "通话结束：主叫="
                            + caller
                            + "，被叫/房间="
                            + calleeOrRoom
                            + "，开始时间="
                            + startedAt
                            + "，时长="
                            + durationSec
                            + "秒"
                            + (wasConnected ? " (已接通)" : " (未接通)"));

                    String httpUrl = httpServerField.getText().trim();
                    httpFileClient.setBaseUrl(httpUrl);
                    new Thread(
                            () -> {
                              try {
                                HttpFileClient.CdrRecord cdr =
                                    httpFileClient.reportCdr(
                                        callId, caller, calleeOrRoom, startedAt, durationSec);
                                Platform.runLater(
                                    () -> {
                                      logs.append(
                                          "统计",
                                          "CDR 通话记录已成功上报后台 (ID="
                                              + cdr.id()
                                              + ", 主叫="
                                              + cdr.caller()
                                              + ", 被叫="
                                              + cdr.calleeOrRoom()
                                              + ", 时长="
                                              + cdr.durationSec()
                                              + "秒)");
                                      refreshCdrAndStats();
                                    });
                              } catch (Exception ex) {
                                Platform.runLater(
                                    () -> {
                                      logs.append("警告", "CDR 上报后台失败：" + ex.getMessage());
                                    });
                              }
                            },
                            "cdr-reporter")
                        .start();
                  });
            }


            @Override
            public void instantMessageReceived(
                String fromUri, String toUri, String body, String contentType) {
              Platform.runLater(
                  () -> {
                    String sender = extractUser(fromUri);
                    String receiver = extractUser(toUri);
                    FileMessage fm = FileMessage.tryParse(body);
                    ChatMessage msg =
                        (fm != null)
                            ? ChatMessage.file(sender, receiver, false, fm, null)
                            : ChatMessage.text(sender, receiver, false, body);
                    chatMessages.add(msg);
                    if (chatListView != null) {
                      chatListView.scrollTo(chatMessages.size() - 1);
                    }
                  });
            }

            @Override
            public void instantMessageStatus(
                String toUri, String body, int statusCode, String reason) {
              Platform.runLater(
                  () -> {
                    String status =
                        (statusCode == 200) ? "已送达" : ("发送未送达(" + statusCode + " " + reason + ")");
                    boolean matched = false;
                    for (int i = 0; i < chatMessages.size(); i++) {
                      ChatMessage m = chatMessages.get(i);
                      if (m.isOutgoing() && ("已发送".equals(m.getStatus()) || m.getStatus().isEmpty())) {
                        String expectedBody = m.isFile() ? m.getFile().toWireFormat() : m.getText();
                        if (body != null && !body.isEmpty() && expectedBody.equals(body)) {
                          m.setStatus(status);
                          matched = true;
                          break;
                        }
                      }
                    }
                    if (!matched) {
                      for (int i = 0; i < chatMessages.size(); i++) {
                        ChatMessage m = chatMessages.get(i);
                        if (m.isOutgoing() && ("已发送".equals(m.getStatus()) || m.getStatus().isEmpty())) {
                          m.setStatus(status);
                          break;
                        }
                      }
                    }
                    if (chatListView != null) {
                      chatListView.refresh();
                    }
                  });
            }
          });

  private Stage primaryStage;
  private LogView logs;
  private Label callStatus;
  private TextField server;
  private TextField httpServerField;
  private ComboBox<String> transport;
  private TextField domain;
  private TextField user;
  private PasswordField password;
  private TextField sipPort;
  private TextField peer;

  private Button startEngine;
  private Button register;
  private Button callAudio;
  private Button callVideo;
  private Button previewBtn;
  private Button answerAudio;
  private Button answerVideo;
  private Button hangup;
  private HBox incomingBox;
  private Label incomingNotice;
  private Label videoBadge;
  private Label videoDetailLabel;
  private Label httpStatusBadge;

  // Chat UI elements
  private ListView<ChatMessage> chatListView;
  private TextField chatInput;
  private Button sendMsgBtn;
  private Button sendFileBtn;
  private Label chatPeerLabel;

  private Label fileProgressLabel;

  // CDR and Stats UI elements
  private final ObservableList<HttpFileClient.CdrRecord> cdrRecords = FXCollections.observableArrayList();
  private TableView<HttpFileClient.CdrRecord> cdrTableView;
  private Label totalCallsLabel;
  private Label totalDurationLabel;
  private Label avgDurationLabel;
  private Label peakMembersLabel;
  private TabPane rightTabPane;
  private Tab chatTab;
  private Button loginBtn;

  private String campusIp = "";


  @Override
  public void start(Stage stage) {
    this.primaryStage = stage;
    Map<String, String> named = getParameters().getNamed();
    LocalPrefs.load();
    campusIp = CampusLan.detect();

    server = new TextField();
    if (!LocalPrefs.server.isEmpty()) {
      server.setText(LocalPrefs.server);
    } else if (!campusIp.isEmpty()) {
      server.setText(campusIp);
    }

    httpServerField = new TextField();
    if (!LocalPrefs.httpServer.isEmpty()) {
      httpServerField.setText(LocalPrefs.httpServer);
    } else {
      httpServerField.setText("http://localhost:8080");
    }
    httpFileClient.setBaseUrl(httpServerField.getText());

    domain = new TextField(named.getOrDefault("domain", "lab"));
    user = new TextField(named.getOrDefault("user", "1001"));
    password = new PasswordField();
    password.setText(named.getOrDefault("password", user.getText().trim()));
    user.textProperty().addListener((obs, oldV, newV) -> {
      if (password.getText().equals(oldV)) {
        password.setText(newV);
      }
    });
    sipPort = new TextField(named.getOrDefault("port", "5060"));
    peer = new TextField(named.getOrDefault("peer", "1002"));
    transport = new ComboBox<>();
    transport.getItems().addAll("tcp", "udp");
    transport.setValue("tcp");
    if ("udp".equalsIgnoreCase(named.getOrDefault("transport", ""))) {
      transport.setValue("udp");
    }
    if (named.containsKey("server")) {
      server.setText(named.get("server"));
    }
    if (named.containsKey("http")) {
      httpServerField.setText(named.get("http"));
      httpFileClient.setBaseUrl(named.get("http"));
    }

    Button fillLan = new Button("填入本机 IP");
    startEngine = new Button("启动引擎");
    register = new Button("注册/上线");
    callAudio = new Button("语音呼叫");
    callVideo = new Button("视频呼叫");
    callVideo.setStyle("-fx-background-color: #1f6feb; -fx-text-fill: white; -fx-font-weight: bold;");
    previewBtn = new Button("摄像头预览");
    answerVideo = new Button("接听(视频)");
    answerVideo.setStyle("-fx-background-color: #1a7f37; -fx-text-fill: white; -fx-font-weight: bold;");
    answerAudio = new Button("接听(语音)");
    hangup = new Button("挂断");
    hangup.setStyle("-fx-background-color: #cf222e; -fx-text-fill: white;");

    Button testHttpBtn = new Button("测试后台");
    httpStatusBadge = new Label("未检测");
    httpStatusBadge.setStyle(
        "-fx-font-size: 11px; -fx-padding: 2 6 2 6; -fx-background-color: #eaeef2;"
            + " -fx-text-fill: #57606a; -fx-background-radius: 4; -fx-border-color: #d0d7de; -fx-border-radius: 4;");

    logs = new LogView();
    callStatus = new Label();
    callStatus.setStyle(
        "-fx-font-size: 13px; -fx-padding: 6 8 6 8; -fx-background-color: #f6f8fa;"
            + " -fx-background-radius: 6; -fx-border-color: #d0d7de; -fx-border-radius: 6;");
    callStatus.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(callStatus, Priority.ALWAYS);

    fillLan.setOnAction(
        e -> {
          if (campusIp.isEmpty()) {
            logs.appendApp("没有探测到校园网 IPv4（跳过了回环/虚拟网卡）。");
            return;
          }
          server.setText(campusIp);
          if (!CampusLan.probeTcp(campusIp) && CampusLan.probeTcp("127.0.0.1")) {
            logs.appendApp(
                "本机访问 "
                    + campusIp
                    + ":5060 不通（Docker Desktop 常见）。同机注册请改成 127.0.0.1；Mac 仍填 "
                    + campusIp
                    + "。");
          }
        });

    testHttpBtn.setOnAction(
        e -> {
          String url = httpServerField.getText().trim();
          httpFileClient.setBaseUrl(url);
          logs.append("文件", "正在测试后台 HTTP 服务: " + url + "...");
          new Thread(
                  () -> {
                    boolean healthy = httpFileClient.isHealthy();
                    String authErr = "";
                    try {
                      String tok = httpFileClient.login(user.getText().trim(), password.getText());
                      if (tok != null && !tok.isEmpty()) {
                        authErr = " (鉴权成功)";
                      }
                    } catch (Exception ex) {
                      authErr = " (" + ex.getMessage() + ")";
                    }
                    final String authMsg = authErr;
                    Platform.runLater(
                        () -> {
                          if (healthy) {
                            httpStatusBadge.setText("正常" + (authMsg.contains("成功") ? " 已鉴权" : ""));
                            httpStatusBadge.setStyle(
                                "-fx-font-size: 11px; -fx-padding: 2 6 2 6; -fx-background-color: #dafbe1;"
                                    + " -fx-text-fill: #1a7f37; -fx-font-weight: bold; -fx-background-radius: 4; -fx-border-color: #4ac26b; -fx-border-radius: 4;");
                            logs.append("文件", "后台服务正常 " + url + authMsg);
                          } else {
                            httpStatusBadge.setText("不可达");
                            httpStatusBadge.setStyle(
                                "-fx-font-size: 11px; -fx-padding: 2 6 2 6; -fx-background-color: #ffebe9;"
                                    + " -fx-text-fill: #cf222e; -fx-font-weight: bold; -fx-background-radius: 4; -fx-border-color: #ff8182; -fx-border-radius: 4;");
                            logs.append("警告", "后台 HTTP 连接失败: " + url + "，请确认 Spring Boot 已启动");
                          }
                          LocalPrefs.save(server.getText(), "tcp", url);
                        });
                  },
                  "http-probe")
              .start();
        });

    startEngine.setOnAction(
        e ->
            run(
                () -> {
                  int bound =
                      sip.start(Integer.parseInt(sipPort.getText().trim()), campusIp);
                  sipPort.setText(Integer.toString(bound));
                }));

    register.setOnAction(
        e ->
            run(
                () -> {
                  httpFileClient.setBaseUrl(httpServerField.getText().trim());
                  performLogin(true);
                  sip.register(
                      server.getText(),
                      user.getText().trim(),
                      password.getText(),
                      domain.getText().trim(),
                      transport.getValue());
                }));

    callAudio.setOnAction(
        e -> run(() -> sip.call(peer.getText().trim(), domain.getText().trim(), false)));
    callVideo.setOnAction(
        e -> run(() -> sip.call(peer.getText().trim(), domain.getText().trim(), true)));

    answerAudio.setOnAction(e -> run(() -> sip.answer(false)));
    answerVideo.setOnAction(e -> run(() -> sip.answer(true)));
    hangup.setOnAction(e -> run(sip::hangup));

    previewBtn.setOnAction(
        e ->
            run(
                () -> {
                  if (sip.isPreviewActive()) {
                    sip.stopPreview();
                  } else {
                    sip.startPreview();
                  }
                }));

    // 呼叫与拨号面板
    HBox dialRow = new HBox(8);
    dialRow.setAlignment(Pos.CENTER_LEFT);
    Label peerLabel = new Label("呼叫/聊天:");
    peerLabel.setStyle("-fx-font-weight: bold;");

    Button chip1001 = new Button("1001");
    chip1001.setOnAction(e -> peer.setText("1001"));
    Button chip1002 = new Button("1002");
    chip1002.setOnAction(e -> peer.setText("1002"));
    Button chip1003 = new Button("1003");
    chip1003.setOnAction(e -> peer.setText("1003"));
    Button chipRoom = new Button("room-101");
    chipRoom.setOnAction(e -> peer.setText("room-101"));
    HBox quickPeers = new HBox(4, new Label("快捷:"), chip1001, chip1002, chip1003, chipRoom);
    quickPeers.setAlignment(Pos.CENTER_LEFT);

    HBox callActions = new HBox(8, callVideo, callAudio, hangup);
    callActions.setAlignment(Pos.CENTER_LEFT);

    dialRow.getChildren().addAll(peerLabel, peer, quickPeers, callActions);
    HBox.setHgrow(peer, Priority.NEVER);

    // 来电提示横幅
    incomingNotice = new Label("收到来电");
    incomingNotice.setStyle("-fx-font-weight: bold; -fx-text-fill: #1a7f37;");
    incomingBox = new HBox(10, incomingNotice, answerVideo, answerAudio);
    incomingBox.setAlignment(Pos.CENTER_LEFT);
    incomingBox.setPadding(new Insets(6, 10, 6, 10));
    incomingBox.setStyle(
        "-fx-background-color: #dafbe1; -fx-background-radius: 6; -fx-border-color: #4ac26b; -fx-border-radius: 6;");
    incomingBox.setVisible(false);
    incomingBox.setManaged(false);

    // 视频功能控制与状态行
    HBox videoTitleRow = new HBox(8);
    videoTitleRow.setAlignment(Pos.CENTER_LEFT);
    Label videoTitle = new Label("视频状态:");
    videoTitle.setStyle("-fx-font-weight: bold;");
    videoBadge = new Label("未检测");
    videoBadge.setStyle(
        "-fx-font-size: 11px; -fx-padding: 2 6 2 6; -fx-background-color: #eaeef2;"
            + " -fx-text-fill: #57606a; -fx-background-radius: 4; -fx-border-color: #d0d7de; -fx-border-radius: 4;");
    videoDetailLabel = new Label("启动引擎后将自动探测 H.264 与采集设备");
    videoDetailLabel.setStyle("-fx-text-fill: #57606a; -fx-font-size: 11px;");
    videoTitleRow.getChildren().addAll(videoTitle, videoBadge, videoDetailLabel);
    HBox.setHgrow(videoTitleRow, Priority.ALWAYS);

    HBox videoRow = new HBox(12, videoTitleRow, previewBtn);
    videoRow.setAlignment(Pos.CENTER_LEFT);
    videoRow.setPadding(new Insets(6, 10, 6, 10));
    videoRow.setStyle(
        "-fx-background-color: #f6f8fa; -fx-border-color: #d0d7de; -fx-border-radius: 6; -fx-background-radius: 6;");

    VBox callCard = new VBox(8, dialRow, incomingBox, videoRow);
    callCard.setPadding(new Insets(10));
    callCard.setStyle(
        "-fx-background-color: #ffffff; -fx-border-color: #d0d7de; -fx-border-radius: 8; -fx-background-radius: 8;");

    // 配置折叠栏
    GridPane cfgGrid = new GridPane();
    cfgGrid.setHgap(8);
    cfgGrid.setVgap(8);
    cfgGrid.setPadding(new Insets(8));
    int r = 0;
    cfgGrid.add(new Label("SIP 服务器"), 0, r);
    HBox serverRow = new HBox(8, server, fillLan);
    HBox.setHgrow(server, Priority.ALWAYS);
    cfgGrid.add(serverRow, 1, r++);

    cfgGrid.add(new Label("HTTP 后台"), 0, r);
    HBox httpRow = new HBox(8, httpServerField, testHttpBtn, httpStatusBadge);
    httpRow.setAlignment(Pos.CENTER_LEFT);
    HBox.setHgrow(httpServerField, Priority.ALWAYS);
    cfgGrid.add(httpRow, 1, r++);

    cfgGrid.add(new Label("传输"), 0, r);
    cfgGrid.add(transport, 1, r++);
    cfgGrid.add(new Label("域"), 0, r);
    cfgGrid.add(domain, 1, r++);
    cfgGrid.add(new Label("用户"), 0, r);
    cfgGrid.add(user, 1, r++);
    cfgGrid.add(new Label("密码"), 0, r);
    cfgGrid.add(password, 1, r++);
    cfgGrid.add(new Label("本机 SIP 端口"), 0, r);
    cfgGrid.add(sipPort, 1, r++);

    loginBtn = new Button("登录后台");
    loginBtn.setStyle("-fx-background-color: #0969da; -fx-text-fill: white; -fx-font-weight: bold;");
    loginBtn.setOnAction(e -> performLogin(false));

    HBox cfgButtons = new HBox(8, startEngine, register, loginBtn);
    cfgGrid.add(cfgButtons, 0, r++, 2, 1);

    GridPane.setHgrow(serverRow, Priority.ALWAYS);

    TitledPane cfgPane = new TitledPane("网络与账号设置 (Kamailio / Spring Boot 后台)", cfgGrid);
    cfgPane.setExpanded(true);

    // 顶部状态栏
    HBox topStatus = new HBox(8, callStatus);
    topStatus.setAlignment(Pos.CENTER_LEFT);

    VBox leftPane = new VBox(10, topStatus, callCard, cfgPane, logs);
    leftPane.setPadding(new Insets(10));
    leftPane.setMinWidth(420);
    VBox.setVgrow(logs, Priority.ALWAYS);

    // ======== 右侧面板：消息与文件传输 (SIP MESSAGE + HTTP 文件) ========
    chatPeerLabel = new Label("当前对象: " + peer.getText().trim());
    chatPeerLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #57606a;");
    peer.textProperty().addListener((obs, oldV, newV) -> chatPeerLabel.setText("当前对象: " + newV.trim()));

    Label chatHeaderTitle = new Label("💬 消息与文件传输");
    chatHeaderTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
    Button clearChatBtn = new Button("清空");
    clearChatBtn.setOnAction(e -> chatMessages.clear());

    Region chatSpacer = new Region();
    HBox.setHgrow(chatSpacer, Priority.ALWAYS);
    HBox chatHeader = new HBox(8, chatHeaderTitle, chatPeerLabel, chatSpacer, clearChatBtn);
    chatHeader.setAlignment(Pos.CENTER_LEFT);
    chatHeader.setPadding(new Insets(4, 4, 4, 4));

    chatListView = new ListView<>(chatMessages);
    chatListView.setCellFactory(lv -> new ChatMessageCell());
    VBox.setVgrow(chatListView, Priority.ALWAYS);

    chatInput = new TextField();
    chatInput.setPromptText("输入文字消息，按 Enter 发送...");
    HBox.setHgrow(chatInput, Priority.ALWAYS);
    chatInput.setOnAction(e -> sendTextMessage());

    sendMsgBtn = new Button("发送");
    sendMsgBtn.setStyle("-fx-background-color: #1f6feb; -fx-text-fill: white; -fx-font-weight: bold;");
    sendMsgBtn.setOnAction(e -> sendTextMessage());

    sendFileBtn = new Button("📎 发文件...");
    sendFileBtn.setStyle("-fx-background-color: #2da44e; -fx-text-fill: white; -fx-font-weight: bold;");
    sendFileBtn.setOnAction(e -> sendFile(primaryStage));

    fileProgressLabel = new Label();
    fileProgressLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #0969da;");
    fileProgressLabel.setVisible(false);
    fileProgressLabel.setManaged(false);

    HBox inputActions = new HBox(6, chatInput, sendMsgBtn, sendFileBtn);
    inputActions.setAlignment(Pos.CENTER_LEFT);

    VBox chatInputBox = new VBox(6, fileProgressLabel, inputActions);
    chatInputBox.setPadding(new Insets(6, 0, 0, 0));

    VBox rightPane = new VBox(8, chatHeader, chatListView, chatInputBox);
    rightPane.setPadding(new Insets(10));
    rightPane.setMinWidth(360);
    rightPane.setStyle(
        "-fx-background-color: #ffffff; -fx-border-color: #d0d7de; -fx-border-radius: 8; -fx-background-radius: 8;");

    rightTabPane = new TabPane();
    chatTab = new Tab("💬 消息与文件", rightPane);
    chatTab.setClosable(false);

    VBox cdrPane = buildCdrPane();
    Tab cdrTab = new Tab("📊 通话记录与统计 (CDR)", cdrPane);
    cdrTab.setClosable(false);

    rightTabPane.getTabs().addAll(chatTab, cdrTab);

    SplitPane splitPane = new SplitPane();
    splitPane.setOrientation(Orientation.HORIZONTAL);
    splitPane.getItems().addAll(leftPane, rightTabPane);
    splitPane.setDividerPositions(0.48);

    stage.setTitle("SIP 客户端 · 阶段 4 后台统计与通话记录");
    stage.setScene(new Scene(splitPane, 1020, 740));
    stage.setOnCloseRequest(e -> sip.shutdown());
    stage.show();

    logs.appendApp("SIP 服务器 = Kamailio 校园网 IP；HTTP 后台 = Spring Boot 端口（用于文件与 CDR 统计）。");
    logs.appendApp(
        campusIp.isEmpty()
            ? "未探测到校园网 IPv4。"
            : "本机校园网：" + campusIp);
    String filled = server.getText() == null ? "" : server.getText().trim();
    if (!named.containsKey("server")
        && !CampusLan.probeTcp(filled.isEmpty() ? campusIp : filled)
        && CampusLan.probeTcp("127.0.0.1")) {
      server.setText("127.0.0.1");
      logs.appendApp(
          "本机连校园网 IP:5060 不通（Docker Desktop 连自己的局域网地址会超时），"
              + "SIP 服务器已改为 127.0.0.1。Mac 端仍填 "
              + campusIp
              + "。");
    } else if (!LocalPrefs.server.isEmpty()) {
      if (CampusLan.probeTcp(LocalPrefs.server)) {
        logs.appendApp("上次代理 " + LocalPrefs.server + " TCP 可达。");
      } else {
        logs.appendApp(
            "上次代理不可达，可点「填入本机 IP」或问信令要新 IP（当前仍填着 "
                + LocalPrefs.server
                + "）。");
      }
    }
    logs.appendApp("阶段 4 后台统计：对接登录鉴权与通话结束自动上报 CDR，支持后台拉历史与通话统计。");
    logs.appendApp("同机第二窗口：mvn javafx:run -Djavafx.args=\"--user=1002 --port=5062 --peer=1001\"");
    refreshButtons();
    performLogin(true);
  }


  private void sendTextMessage() {
    String text = chatInput.getText().trim();
    if (text.isEmpty()) {
      return;
    }
    String targetPeer = peer.getText().trim();
    String targetDomain = domain.getText().trim();
    String myUser = user.getText().trim();
    run(
        () -> {
          sip.sendMessage(targetPeer, targetDomain, text);
          ChatMessage msg = ChatMessage.text(myUser, targetPeer, true, text);
          chatMessages.add(msg);
          chatInput.clear();
          if (chatListView != null) {
            chatListView.scrollTo(chatMessages.size() - 1);
          }
        });
  }

  private void sendFile(Stage owner) {
    if (!sip.isRegistered()) {
      logs.append("警告", "尚未注册成功，无法发送文件消息");
      return;
    }
    FileChooser chooser = new FileChooser();
    chooser.setTitle("选择要发送的文件");
    File file = chooser.showOpenDialog(owner);
    if (file == null) {
      return;
    }

    String targetPeer = peer.getText().trim();
    String targetDomain = domain.getText().trim();
    String myUser = user.getText().trim();
    String httpUrl = httpServerField.getText().trim();
    httpFileClient.setBaseUrl(httpUrl);

    fileProgressLabel.setText("正在上传 " + file.getName() + " (" + FileMessage.formatSize(file.length()) + ") 到后台...");
    fileProgressLabel.setVisible(true);
    fileProgressLabel.setManaged(true);
    sendFileBtn.setDisable(true);

    logs.append("文件", "正在上传文件 " + file.getName() + " 到后台 " + httpUrl + "...");

    new Thread(
            () -> {
              try {
                FileMessage fm = httpFileClient.uploadFile(file.toPath(), myUser, targetPeer);
                Platform.runLater(
                    () -> {
                      fileProgressLabel.setText("上传完成，正在发送 SIP MESSAGE 通知...");
                      run(
                          () -> {
                            sip.sendMessage(targetPeer, targetDomain, fm.toWireFormat());
                            ChatMessage msg = ChatMessage.file(myUser, targetPeer, true, fm, file.toPath());
                            chatMessages.add(msg);
                            if (chatListView != null) {
                              chatListView.scrollTo(chatMessages.size() - 1);
                            }
                            logs.append("文件", "文件通知已发送: " + fm.getName() + " (" + FileMessage.formatSize(fm.getSize()) + ")");
                          });
                    });
              } catch (Exception ex) {
                Platform.runLater(
                    () -> {
                      logs.append("错误", "文件上传失败：" + ex.getMessage());
                    });
              } finally {
                Platform.runLater(
                    () -> {
                      fileProgressLabel.setVisible(false);
                      fileProgressLabel.setManaged(false);
                      sendFileBtn.setDisable(!sip.isRegistered());
                    });
              }
            },
            "file-uploader")
        .start();
  }

  private void handleDownloadFile(ChatMessage item) {
    if (!item.isFile()) {
      return;
    }
    FileMessage fm = item.getFile();
    FileChooser chooser = new FileChooser();
    chooser.setTitle("保存文件");
    chooser.setInitialFileName(fm.getName());
    File saveTo = chooser.showSaveDialog(primaryStage);
    if (saveTo == null) {
      return;
    }

    fileProgressLabel.setText("正在下载 " + fm.getName() + "...");
    fileProgressLabel.setVisible(true);
    fileProgressLabel.setManaged(true);

    logs.append("文件", "正在从 " + fm.getUrl() + " 下载文件: " + fm.getName() + "...");

    new Thread(
            () -> {
              try {
                Path downloaded = httpFileClient.downloadFile(fm.getUrl(), saveTo.toPath());
                Platform.runLater(
                    () -> {
                      item.setLocalPath(downloaded);
                      if (chatListView != null) {
                        chatListView.refresh();
                      }
                      logs.append("文件", "文件下载成功，已保存至: " + downloaded.toAbsolutePath());
                    });
              } catch (Exception ex) {
                Platform.runLater(
                    () -> {
                      logs.append("错误", "文件下载失败：" + ex.getMessage());
                    });
              } finally {
                Platform.runLater(
                    () -> {
                      fileProgressLabel.setVisible(false);
                      fileProgressLabel.setManaged(false);
                    });
              }
            },
            "file-downloader")
        .start();
  }

  private void refreshButtons() {
    boolean on = sip.isStarted();
    boolean reg = sip.isRegistered();
    boolean incoming = sip.hasIncoming();
    SipService.VideoDetails vd = sip.queryVideoDetails();
    boolean videoSupported = vd.supported;
    boolean hasCapture = vd.captureDevId >= 0;

    startEngine.setDisable(on);
    register.setDisable(!on);
    if (loginBtn != null) {
      loginBtn.setDisable(!on);
    }
    previewBtn.setDisable(!on || !videoSupported || !hasCapture);
    if (!hasCapture && videoSupported) {
      previewBtn.setText("无摄像头");
    } else {
      previewBtn.setText(sip.isPreviewActive() ? "关闭预览" : "摄像头预览");
    }

    callAudio.setDisable(!reg);
    callVideo.setDisable(!reg || !videoSupported);

    answerAudio.setDisable(!incoming);
    answerVideo.setDisable(!incoming || !videoSupported);
    incomingBox.setVisible(incoming);
    incomingBox.setManaged(incoming);
    if (incoming) {
      if (videoSupported) {
        incomingNotice.setText("收到来电：可选择【接听(视频)】或【接听(语音)】");
      } else {
        incomingNotice.setText("收到来电：当前为纯音频模式，请选择【接听(语音)】");
      }
    }

    hangup.setDisable(!on);
    callStatus.setText("状态  " + sip.statusText());

    if (sendMsgBtn != null) {
      sendMsgBtn.setDisable(!reg);
    }
    if (sendFileBtn != null && !fileProgressLabel.isVisible()) {
      sendFileBtn.setDisable(!reg);
    }

    if (!on) {
      videoBadge.setText("引擎未启动");
      videoBadge.setStyle(
          "-fx-font-size: 11px; -fx-padding: 2 6 2 6; -fx-background-color: #eaeef2;"
              + " -fx-text-fill: #57606a; -fx-background-radius: 4; -fx-border-color: #d0d7de; -fx-border-radius: 4;");
      videoDetailLabel.setText("启动引擎后将自动探测 H.264 编解码器与视频输入输出设备。");
    } else if (videoSupported) {
      videoBadge.setText("视频就绪 (" + vd.codec + ")");
      videoBadge.setStyle(
          "-fx-font-size: 11px; -fx-padding: 2 6 2 6; -fx-background-color: #dafbe1;"
              + " -fx-text-fill: #1a7f37; -fx-font-weight: bold; -fx-background-radius: 4; -fx-border-color: #4ac26b; -fx-border-radius: 4;");
      String capDesc = hasCapture ? vd.captureDev : "无可用摄像头（仅可接收画面）";
      videoDetailLabel.setText("采集: " + capDesc + " | 渲染: " + vd.renderDev + " | 接通后拉起独立原生低延迟窗口");
    } else {
      videoBadge.setText("阶段 1 纯音频模式");
      videoBadge.setStyle(
          "-fx-font-size: 11px; -fx-padding: 2 6 2 6; -fx-background-color: #fff8c5;"
              + " -fx-text-fill: #9a6700; -fx-font-weight: bold; -fx-background-radius: 4; -fx-border-color: #d4a72c; -fx-border-radius: 4;");
      videoDetailLabel.setText("底层动态库未启用 H.264（见 native/ 编译指南）；呼叫与接听已自动降级为语音通话。");
    }
  }

  private void performLogin(boolean silent) {
    String url = httpServerField.getText().trim();
    httpFileClient.setBaseUrl(url);
    String u = user.getText().trim();
    String p = password.getText();
    if (!silent) {
      logs.append("系统", "正在登录后台 HTTP 服务: " + url + " (用户 " + u + ")...");
    }
    new Thread(
            () -> {
              try {
                String token = httpFileClient.login(u, p);
                Platform.runLater(
                    () -> {
                      httpStatusBadge.setText("已登录 (" + u + ")");
                      httpStatusBadge.setStyle(
                          "-fx-font-size: 11px; -fx-padding: 2 6 2 6; -fx-background-color: #dafbe1;"
                              + " -fx-text-fill: #1a7f37; -fx-font-weight: bold; -fx-background-radius: 4; -fx-border-color: #4ac26b; -fx-border-radius: 4;");
                      String tokenSample = (token != null && token.length() > 8) ? token.substring(0, 8) + "..." : token;
                      logs.append("系统", "后台登录成功: " + u + " (Token: " + tokenSample + ")");
                      refreshCdrAndStats();
                    });
              } catch (Exception ex) {
                Platform.runLater(
                    () -> {
                      if (!silent) {
                        httpStatusBadge.setText("登录失败");
                        httpStatusBadge.setStyle(
                            "-fx-font-size: 11px; -fx-padding: 2 6 2 6; -fx-background-color: #ffebe9;"
                                + " -fx-text-fill: #cf222e; -fx-font-weight: bold; -fx-background-radius: 4; -fx-border-color: #ff8182; -fx-border-radius: 4;");
                        logs.append("警告", "后台登录失败: " + ex.getMessage());
                      } else {
                        httpStatusBadge.setText("未登录");
                        httpStatusBadge.setStyle(
                            "-fx-font-size: 11px; -fx-padding: 2 6 2 6; -fx-background-color: #fff8c5;"
                                + " -fx-text-fill: #9a6700; -fx-font-weight: bold; -fx-background-radius: 4; -fx-border-color: #d4a72c; -fx-border-radius: 4;");
                        logs.append("提示", "后台静默登录未成功 (" + ex.getMessage() + ")，可确认密码后点击【登录后台】");
                      }
                    });
              }
            },
            "http-login")
        .start();
  }

  private void refreshCdrAndStats() {
    String httpUrl = httpServerField.getText().trim();
    httpFileClient.setBaseUrl(httpUrl);
    fetchCdrHistory("");
    new Thread(
            () -> {
              try {
                HttpFileClient.CdrStats stats = httpFileClient.fetchStats();
                Platform.runLater(
                    () -> {
                      if (totalCallsLabel != null) {
                        totalCallsLabel.setText("通话总数: " + stats.totalCalls() + " 次");
                        totalDurationLabel.setText("总通话时长: " + stats.totalDurationSec() + " 秒");
                        avgDurationLabel.setText("平均时长: " + stats.averageDurationSec() + " 秒");
                        peakMembersLabel.setText("房间峰值: " + stats.peakRoomMembers() + " 人");
                      }
                    });
              } catch (Exception ex) {
                // ignore stats failure
              }
            },
            "cdr-stats-fetcher")
        .start();
  }

  private void fetchCdrHistory(String callerFilter) {
    String httpUrl = httpServerField.getText().trim();
    httpFileClient.setBaseUrl(httpUrl);
    new Thread(
            () -> {
              try {
                List<HttpFileClient.CdrRecord> list = httpFileClient.fetchCdrList(callerFilter, 100);
                Platform.runLater(
                    () -> {
                      cdrRecords.setAll(list);
                    });
              } catch (Exception ex) {
                Platform.runLater(
                    () -> {
                      logs.append("警告", "拉取通话记录失败: " + ex.getMessage());
                    });
              }
            },
            "cdr-list-fetcher")
        .start();
  }

  private VBox buildCdrPane() {
    HBox statsCard = new HBox(12);
    statsCard.setAlignment(Pos.CENTER_LEFT);
    statsCard.setPadding(new Insets(8, 12, 8, 12));
    statsCard.setStyle(
        "-fx-background-color: #f6f8fa; -fx-border-color: #d0d7de; -fx-border-radius: 6; -fx-background-radius: 6;");

    totalCallsLabel = new Label("通话总数: 0 次");
    totalCallsLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #0969da;");

    totalDurationLabel = new Label("总通话时长: 0 秒");
    totalDurationLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #1a7f37;");

    avgDurationLabel = new Label("平均时长: 0.0 秒");
    avgDurationLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #8250df;");

    peakMembersLabel = new Label("房间峰值: 0 人");
    peakMembersLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #cf222e;");

    statsCard.getChildren().addAll(totalCallsLabel, totalDurationLabel, avgDurationLabel, peakMembersLabel);

    Button refreshCdrBtn = new Button("🔄 刷新记录");
    refreshCdrBtn.setOnAction(e -> refreshCdrAndStats());

    Button filterMineBtn = new Button("只看我");
    filterMineBtn.setOnAction(e -> fetchCdrHistory(user.getText().trim()));

    Button filterAllBtn = new Button("查看全部");
    filterAllBtn.setOnAction(e -> fetchCdrHistory(""));

    Label hintLabel = new Label("通话结束自动上报并持久化到后台 SQLite 数据库");
    hintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #57606a;");

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    HBox actionsRow = new HBox(8, refreshCdrBtn, filterMineBtn, filterAllBtn, spacer, hintLabel);
    actionsRow.setAlignment(Pos.CENTER_LEFT);
    actionsRow.setPadding(new Insets(4, 0, 4, 0));

    cdrTableView = new TableView<>(cdrRecords);
    VBox.setVgrow(cdrTableView, Priority.ALWAYS);

    TableColumn<HttpFileClient.CdrRecord, Long> colId = new TableColumn<>("ID");
    colId.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().id()));
    colId.setPrefWidth(55);

    TableColumn<HttpFileClient.CdrRecord, String> colCaller = new TableColumn<>("主叫");
    colCaller.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().caller()));
    colCaller.setPrefWidth(90);

    TableColumn<HttpFileClient.CdrRecord, String> colCallee = new TableColumn<>("被叫/房间");
    colCallee.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().calleeOrRoom()));
    colCallee.setPrefWidth(110);

    TableColumn<HttpFileClient.CdrRecord, String> colTime = new TableColumn<>("开始时间");
    colTime.setCellValueFactory(data -> {
      Instant t = data.getValue().startedAt();
      return new SimpleStringProperty(t != null ? t.toString().replace("T", " ").replace("Z", "") : "-");
    });
    colTime.setPrefWidth(155);

    TableColumn<HttpFileClient.CdrRecord, String> colDur = new TableColumn<>("通话时长");
    colDur.setCellValueFactory(data -> {
      long d = data.getValue().durationSec();
      return new SimpleStringProperty(d > 0 ? (d + " 秒") : "0 秒 (未接通)");
    });
    colDur.setPrefWidth(100);

    TableColumn<HttpFileClient.CdrRecord, Void> colActions = new TableColumn<>("快捷操作");
    colActions.setPrefWidth(125);
    colActions.setCellFactory(param -> new TableCell<>() {
      private final Button chatBtn = new Button("💬");
      private final Button audioBtn = new Button("📞");
      private final Button videoBtn = new Button("📹");
      private final HBox box = new HBox(4, chatBtn, audioBtn, videoBtn);
      {
        box.setAlignment(Pos.CENTER);
        chatBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6 2 6;");
        chatBtn.setTooltip(new Tooltip("切换至消息聊天窗口"));
        audioBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6 2 6;");
        audioBtn.setTooltip(new Tooltip("发起语音呼叫"));
        videoBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6 2 6;");
        videoBtn.setTooltip(new Tooltip("发起视频呼叫"));

        chatBtn.setOnAction(e -> {
          HttpFileClient.CdrRecord sel = getTableView().getItems().get(getIndex());
          selectAndChatWith(sel);
        });
        audioBtn.setOnAction(e -> {
          HttpFileClient.CdrRecord sel = getTableView().getItems().get(getIndex());
          String target = getTargetFromCdr(sel);
          if (!target.isEmpty()) {
            peer.setText(target);
            run(() -> sip.call(target, domain.getText().trim(), false));
          }
        });
        videoBtn.setOnAction(e -> {
          HttpFileClient.CdrRecord sel = getTableView().getItems().get(getIndex());
          String target = getTargetFromCdr(sel);
          if (!target.isEmpty()) {
            peer.setText(target);
            run(() -> sip.call(target, domain.getText().trim(), true));
          }
        });
      }

      @Override
      protected void updateItem(Void item, boolean empty) {
        super.updateItem(item, empty);
        setGraphic(empty ? null : box);
      }
    });

    cdrTableView.getColumns().addAll(colId, colCaller, colCallee, colTime, colDur, colActions);
    cdrTableView.setPlaceholder(new Label("暂无通话记录 (通话结束后将自动显示并同步后台)"));

    cdrTableView.setRowFactory(tv -> {
      TableRow<HttpFileClient.CdrRecord> row = new TableRow<>();
      ContextMenu menu = new ContextMenu();

      MenuItem chatItem = new MenuItem("💬 切换至消息聊天");
      chatItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          selectAndChatWith(row.getItem());
        }
      });

      MenuItem callAudioItem = new MenuItem("📞 发起语音呼叫");
      callAudioItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          String target = getTargetFromCdr(row.getItem());
          peer.setText(target);
          run(() -> sip.call(target, domain.getText().trim(), false));
        }
      });

      MenuItem callVideoItem = new MenuItem("📹 发起视频呼叫");
      callVideoItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          String target = getTargetFromCdr(row.getItem());
          peer.setText(target);
          run(() -> sip.call(target, domain.getText().trim(), true));
        }
      });

      MenuItem copyItem = new MenuItem("📋 复制对方号码/房间");
      copyItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          String target = getTargetFromCdr(row.getItem());
          ClipboardContent content = new ClipboardContent();
          content.putString(target);
          Clipboard.getSystemClipboard().setContent(content);
          logs.append("操作", "已复制到剪贴板：" + target);
        }
      });

      menu.getItems().addAll(chatItem, callAudioItem, callVideoItem, copyItem);

      row.contextMenuProperty().bind(
          Bindings.when(row.emptyProperty())
              .then((ContextMenu) null)
              .otherwise(menu));

      row.setOnMouseClicked(event -> {
        if (event.getClickCount() == 2 && !row.isEmpty()) {
          selectAndChatWith(row.getItem());
        }
      });

      return row;
    });

    VBox pane = new VBox(8, statsCard, actionsRow, cdrTableView);
    pane.setPadding(new Insets(10));
    pane.setStyle(
        "-fx-background-color: #ffffff; -fx-border-color: #d0d7de; -fx-border-radius: 8; -fx-background-radius: 8;");
    return pane;
  }

  private String getTargetFromCdr(HttpFileClient.CdrRecord rec) {
    if (rec == null) {
      return "";
    }
    String my = user.getText().trim();
    return my.equals(rec.caller()) ? rec.calleeOrRoom() : rec.caller();
  }

  private void selectAndChatWith(HttpFileClient.CdrRecord rec) {
    String target = getTargetFromCdr(rec);
    if (!target.isEmpty()) {
      peer.setText(target);
      if (rightTabPane != null && chatTab != null) {
        rightTabPane.getSelectionModel().select(chatTab);
      }
      if (chatInput != null) {
        chatInput.requestFocus();
      }
      logs.append("操作", "已选择并切换至联系人/房间 " + target + "，可直接输入消息或发起呼叫");
    }
  }

  private void run(Throwing action) {
    try {
      action.run();
    } catch (Throwable ex) {
      logs.append("错误", ex.getClass().getSimpleName() + "：" + ex.getMessage());
    }
    refreshButtons();
  }

  static String extractUser(String uri) {
    if (uri == null || uri.isBlank()) {
      return "";
    }
    String s = uri.trim();
    int sipIdx = s.indexOf("sip:");
    if (sipIdx != -1) {
      s = s.substring(sipIdx + 4);
    }
    int atIdx = s.indexOf('@');
    if (atIdx != -1) {
      s = s.substring(0, atIdx);
    }
    int semiIdx = s.indexOf(';');
    if (semiIdx != -1) {
      s = s.substring(0, semiIdx);
    }
    int colonIdx = s.indexOf(':');
    if (colonIdx != -1) {
      s = s.substring(0, colonIdx);
    }
    int gtIdx = s.indexOf('>');
    if (gtIdx != -1) {
      s = s.substring(0, gtIdx);
    }
    return s.trim();
  }

  private final class ChatMessageCell extends ListCell<ChatMessage> {
    private final VBox root = new VBox(4);
    private final HBox header = new HBox(8);
    private final Label metaLabel = new Label();
    private final Label statusLabel = new Label();
    private final Label textLabel = new Label();
    private final VBox fileBox = new VBox(4);
    private final Label fileNameLabel = new Label();
    private final Label fileSizeLabel = new Label();
    private final ImageView previewImageView = new ImageView();
    private final HBox fileActionBox = new HBox(6);
    private final Button downloadBtn = new Button();
    private final Button openBtn = new Button("打开");

    ChatMessageCell() {
      metaLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
      statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #57606a;");
      textLabel.setWrapText(true);
      textLabel.setStyle("-fx-font-size: 13px; -fx-padding: 4 8 4 8; -fx-background-radius: 6;");

      fileNameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
      fileSizeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #57606a;");
      previewImageView.setFitWidth(180);
      previewImageView.setPreserveRatio(true);
      previewImageView.setSmooth(true);
      previewImageView.setVisible(false);
      previewImageView.setManaged(false);
      previewImageView.setStyle(
          "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.12), 4, 0, 0, 1);");

      fileBox.setStyle(
          "-fx-background-color: #f6f8fa; -fx-border-color: #d0d7de; -fx-border-radius: 6;"
              + " -fx-background-radius: 6; -fx-padding: 6 8 6 8;");
      fileActionBox.getChildren().addAll(downloadBtn, openBtn);
      fileBox.getChildren().addAll(fileNameLabel, fileSizeLabel, previewImageView, fileActionBox);

      header.getChildren().addAll(metaLabel, statusLabel);
      root.getChildren().addAll(header, textLabel, fileBox);
      root.setPadding(new Insets(4, 6, 4, 6));
    }

    @Override
    protected void updateItem(ChatMessage item, boolean empty) {
      super.updateItem(item, empty);
      if (empty || item == null) {
        setGraphic(null);
        return;
      }

      String time = item.formatTime();
      if (item.isOutgoing()) {
        metaLabel.setText("我 (" + item.getSender() + ")  " + time);
        metaLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #0969da;");
        root.setAlignment(Pos.CENTER_RIGHT);
        header.setAlignment(Pos.CENTER_RIGHT);
        textLabel.setStyle(
            "-fx-font-size: 13px; -fx-padding: 6 10 6 10; -fx-background-color: #ddf4ff;"
                + " -fx-background-radius: 8; -fx-text-fill: #0969da;");
      } else {
        metaLabel.setText(item.getSender() + "  " + time);
        metaLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #1a7f37;");
        metaLabel.setOnMouseClicked(e -> peer.setText(item.getSender()));
        metaLabel.setCursor(javafx.scene.Cursor.HAND);
        root.setAlignment(Pos.CENTER_LEFT);
        header.setAlignment(Pos.CENTER_LEFT);
        textLabel.setStyle(
            "-fx-font-size: 13px; -fx-padding: 6 10 6 10; -fx-background-color: #f6f8fa;"
                + " -fx-border-color: #d0d7de; -fx-border-radius: 8; -fx-background-radius: 8;");
      }

      statusLabel.setText(item.getStatus());
      if ("已送达".equals(item.getStatus())) {
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #1a7f37;");
      } else if (item.getStatus().startsWith("发送未送达")) {
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #cf222e;");
      } else {
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #57606a;");
      }

      if (item.isFile()) {
        textLabel.setVisible(false);
        textLabel.setManaged(false);
        fileBox.setVisible(true);
        fileBox.setManaged(true);

        FileMessage fm = item.getFile();
        String lower = fm.getName().toLowerCase();
        String icon = "📄 ";
        boolean isImage = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
        if (isImage) {
          icon = "🖼️ ";
        } else if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".m4a")) {
          icon = "🎵 ";
        } else if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") || lower.endsWith(".avi")) {
          icon = "🎬 ";
        } else if (lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".7z")) {
          icon = "📦 ";
        }
        fileNameLabel.setText(icon + fm.getName());
        fileSizeLabel.setText(FileMessage.formatSize(fm.getSize()) + " · " + fm.getUrl());

        boolean hasLocal = item.getLocalPath() != null && Files.exists(item.getLocalPath());
        if (hasLocal && isImage) {
          try {
            Image img = new Image(item.getLocalPath().toUri().toString(), 180, 0, true, true);
            previewImageView.setImage(img);
            previewImageView.setVisible(true);
            previewImageView.setManaged(true);
            previewImageView.setOnMouseClicked(
                e -> {
                  try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                      Desktop.getDesktop().open(item.getLocalPath().toFile());
                    } else {
                      getHostServices().showDocument(item.getLocalPath().toUri().toString());
                    }
                  } catch (Exception ex) {
                    logs.append("错误", "打开图片失败: " + ex.getMessage());
                  }
                });
            previewImageView.setCursor(javafx.scene.Cursor.HAND);
          } catch (Exception e) {
            previewImageView.setImage(null);
            previewImageView.setVisible(false);
            previewImageView.setManaged(false);
          }
        } else {
          previewImageView.setImage(null);
          previewImageView.setVisible(false);
          previewImageView.setManaged(false);
        }

        openBtn.setVisible(hasLocal);
        openBtn.setManaged(hasLocal);
        openBtn.setOnAction(
            e -> {
              try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                  Desktop.getDesktop().open(item.getLocalPath().toFile());
                } else {
                  getHostServices().showDocument(item.getLocalPath().toUri().toString());
                }
              } catch (Exception ex) {
                logs.append("错误", "打开文件失败: " + ex.getMessage());
              }
            });

        downloadBtn.setText(hasLocal ? "另存为..." : "下载文件");
        downloadBtn.setOnAction(e -> handleDownloadFile(item));
      } else {
        fileBox.setVisible(false);
        fileBox.setManaged(false);
        textLabel.setVisible(true);
        textLabel.setManaged(true);
        textLabel.setText(item.getText());
      }

      setGraphic(root);
    }
  }

  @FunctionalInterface
  private interface Throwing {
    void run() throws Exception;
  }

  public static void main(String[] args) {
    launch(args);
  }
}
