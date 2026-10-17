package lab.sip.client;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Commercial-grade UI layout assembler for SIP client.
 * Strictly zero external dependencies - wraps existing control instances with modern CSS and layouts.
 */
final class MainViewAssembler {

  private LogView signalingDrawer;
  private boolean drawerExpanded = false;
  private Stage settingsModalStage;
  private Button consoleBtn;
  private Button oneClickOnlineBtn;

  /**
   * Assembles the 3-column commercial-grade workbench layout.
   */
  BorderPane assembleProductUi(
      Stage primaryStage,
      // 1. Status & Call controls
      Label engineStatusLabel,
      Label videoCodecHintLabel,
      Label httpLoginBadgeLabel,
      TextField callTargetField,
      Button[] quickDialButtons,
      Button videoCallBtn,
      Button audioCallBtn,
      Button hangupBtn,
      Button cameraPreviewBtn,
      HBox incomingBox,
      // 2. Settings & Account controls
      TextField sipServerField,
      Button fillLocalIpBtn,
      TextField httpBackendField,
      Button testBackendBtn,
      ComboBox<String> transportBox,
      TextField domainField,
      TextField usernameField,
      PasswordField passwordField,
      TextField localPortField,
      Button startEngineBtn,
      Button registerSipBtn,
      Button loginBackendBtn,
      // 3. Signaling drawer (LogView)
      LogView logView,
      // 4. Main TabPane
      TabPane mainTabPane) {

    BorderPane root = new BorderPane();

    // 1. Initialize settings modal stage (reusing existing form controls)
    initSettingsModal(
        primaryStage,
        sipServerField,
        fillLocalIpBtn,
        httpBackendField,
        testBackendBtn,
        httpLoginBadgeLabel,
        transportBox,
        domainField,
        usernameField,
        passwordField,
        localPortField,
        startEngineBtn,
        registerSipBtn,
        loginBackendBtn);

    // 2. Dark vertical navigation rail
    VBox navRail = buildLeftNavRail(usernameField, mainTabPane, logView);
    root.setLeft(navRail);

    // 3. Secondary sidebar: status capsules, one-click online, direct dial, quick contacts
    VBox secondarySidebar =
        buildSecondarySidebar(
            engineStatusLabel,
            httpLoginBadgeLabel,
            callTargetField,
            quickDialButtons,
            startEngineBtn,
            registerSipBtn,
            loginBackendBtn);

    // 4. Main workspace
    BorderPane workspace = new BorderPane();

    HBox topActionBar =
        buildTopActionBar(
            videoCodecHintLabel,
            cameraPreviewBtn,
            videoCallBtn,
            audioCallBtn,
            hangupBtn);

    VBox topContainer = new VBox(topActionBar);
    if (incomingBox != null) {
      topContainer.getChildren().add(incomingBox);
    }
    workspace.setTop(topContainer);

    mainTabPane.getStyleClass().add("modern-tab-pane");
    workspace.setCenter(mainTabPane);

    this.signalingDrawer = logView;

    HBox centerSplit = new HBox(secondarySidebar, workspace);
    HBox.setHgrow(workspace, Priority.ALWAYS);
    root.setCenter(centerSplit);

    return root;
  }

  private VBox buildLeftNavRail(TextField usernameField, TabPane mainTabPane, LogView logView) {
    VBox rail = new VBox(14);
    rail.getStyleClass().add("nav-rail");
    rail.setPrefWidth(68);
    rail.setAlignment(Pos.TOP_CENTER);

    // Avatar
    StackPane avatarPane = new StackPane();
    Circle bg = new Circle(18, Color.web("#2481CC"));
    Label userLbl = new Label();
    userLbl.textProperty().bind(usernameField.textProperty());
    userLbl.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;");
    avatarPane.getChildren().addAll(bg, userLbl);

    // Core business tabs: 0 = 消息, 1 = 会议室, 2 = 统计
    Button msgBtn = createSvgNavBtn("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z", "消息", true);
    Button mcuBtn = createSvgNavBtn("M23 7l-7 5 7 5V7z M1 5h15v14H1z", "会议室", false);
    Button cdrBtn = createSvgNavBtn("M18 20V10 M12 20V4 M6 20v-6", "统计", false);

    Button[] navBtns = {msgBtn, mcuBtn, cdrBtn};
    for (int i = 0; i < navBtns.length; i++) {
      final int tabIndex = i;
      navBtns[i].setOnAction(
          e -> {
            if (tabIndex < mainTabPane.getTabs().size()) {
              mainTabPane.getSelectionModel().select(tabIndex);
            }
          });
    }

    // Bidirectional sync with TabPane selection
    mainTabPane
        .getSelectionModel()
        .selectedIndexProperty()
        .addListener(
            (obs, oldV, newV) -> {
              int idx = newV.intValue();
              for (int i = 0; i < navBtns.length; i++) {
                boolean active = (i == idx);
                navBtns[i].getStyleClass().setAll(active ? "nav-btn-active" : "nav-btn");
                if (navBtns[i].getGraphic() instanceof SVGPath sp) {
                  sp.setStroke(active ? Color.web("#2481CC") : Color.web("#5A6672"));
                }
              }
              boolean consoleActive = (idx == 3);
              if (consoleBtn != null) {
                consoleBtn.getStyleClass().setAll(consoleActive ? "nav-btn-active" : "nav-btn");
                if (consoleBtn.getGraphic() instanceof SVGPath sp) {
                  sp.setStroke(consoleActive ? Color.web("#2481CC") : Color.web("#5A6672"));
                }
              }
            });

    Region spacer = new Region();
    VBox.setVgrow(spacer, Priority.ALWAYS);

    // Bottom toggles: signaling console & settings modal
    consoleBtn = createSvgNavBtn("M4 17l6-6-6-6 M12 19h8", "信令台", false);
    consoleBtn.setOnAction(
        e -> {
          if (mainTabPane.getTabs().size() > 3) {
            mainTabPane.getSelectionModel().select(3);
          }
          if (logView != null && logView.isDetached()) {
            logView.showDetachedWindow();
          }
        });

    Button configBtn = createSvgNavBtn("M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "设置", false);
    configBtn.setOnAction(
        e -> {
          if (settingsModalStage != null) {
            settingsModalStage.show();
            settingsModalStage.toFront();
          }
        });

    rail.getChildren().addAll(avatarPane, msgBtn, mcuBtn, cdrBtn, spacer, consoleBtn, configBtn);
    return rail;
  }

  private VBox buildSecondarySidebar(
      Label engineStatusLabel,
      Label httpLoginBadgeLabel,
      TextField callTargetField,
      Button[] quickDialButtons,
      Button startEngineBtn,
      Button registerSipBtn,
      Button loginBackendBtn) {
    VBox sidebar = new VBox(12);
    sidebar.getStyleClass().add("secondary-sidebar");
    sidebar.setMinWidth(260);
    sidebar.setMaxWidth(260);
    sidebar.setPrefWidth(260);

    // Status capsules
    HBox badgeRow = new HBox(8);
    badgeRow.setAlignment(Pos.CENTER_LEFT);
    engineStatusLabel.getStyleClass().setAll("badge-offline");
    httpLoginBadgeLabel.getStyleClass().setAll("badge-warn");
    badgeRow.getChildren().addAll(engineStatusLabel, httpLoginBadgeLabel);

    // One-click online flow
    this.oneClickOnlineBtn = new Button("一键启动并上线");
    oneClickOnlineBtn.getStyleClass().add("btn-primary");
    oneClickOnlineBtn.setMaxWidth(Double.MAX_VALUE);
    oneClickOnlineBtn.setOnAction(
        e ->
            runOneClickConnect(
                oneClickOnlineBtn,
                engineStatusLabel,
                httpLoginBadgeLabel,
                startEngineBtn,
                registerSipBtn,
                loginBackendBtn));

    Button openConfigLink = new Button("网络与账号设置...");
    openConfigLink.getStyleClass().add("btn-secondary");
    openConfigLink.setMaxWidth(Double.MAX_VALUE);
    openConfigLink.setOnAction(
        e -> {
          if (settingsModalStage != null) {
            settingsModalStage.show();
            settingsModalStage.toFront();
          }
        });

    // Direct dial
    Label dialTitle = new Label("呼叫或聊天目标");
    dialTitle.getStyleClass().add("section-label");
    callTargetField.setPromptText("输入 SIP 号码或房间 (如 1002)");

    // Quick dial contact cards (Telegram style)
    Label quickTitle = new Label("联系人与研讨室");
    quickTitle.getStyleClass().add("section-label");
    VBox quickList = new VBox(6);
    for (Button btn : quickDialButtons) {
      formatContactButton(btn);
      btn.setMaxWidth(Double.MAX_VALUE);
      quickList.getChildren().add(btn);
    }

    sidebar.getChildren().addAll(
        badgeRow,
        oneClickOnlineBtn,
        openConfigLink,
        new Separator(),
        dialTitle,
        callTargetField,
        quickTitle,
        quickList);
    return sidebar;
  }

  private void formatContactButton(Button btn) {
    String original = btn.getText();
    if (original == null) {
      original = "";
    }
    String badgeText = "1";
    String badgeColor = "#2481CC";
    String primaryText = original;
    String secondaryText = "在线";

    if (original.contains("1001")) {
      badgeText = "1";
      badgeColor = "#2481CC";
      primaryText = "1001";
      secondaryText = "测试账号 1 · 在线";
    } else if (original.contains("1002")) {
      badgeText = "2";
      badgeColor = "#10B981";
      primaryText = "1002";
      secondaryText = "测试账号 2 · 在线";
    } else if (original.contains("1003")) {
      badgeText = "3";
      badgeColor = "#8B5CF6";
      primaryText = "1003";
      secondaryText = "测试账号 3 · 在线";
    } else if (original.contains("room")) {
      badgeText = "M";
      badgeColor = "#F59E0B";
      primaryText = "room-101";
      secondaryText = "MCU 研讨室 (多方)";
    }

    HBox card = new HBox(10);
    card.setAlignment(Pos.CENTER_LEFT);

    StackPane avatar = new StackPane();
    Circle circle = new Circle(15, Color.web(badgeColor));
    Label badgeLbl = new Label(badgeText);
    badgeLbl.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;");
    avatar.getChildren().addAll(circle, badgeLbl);

    VBox textBox = new VBox(2);
    Label nameLbl = new Label(primaryText);
    nameLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12.5px; -fx-text-fill: #1E293B;");
    Label subLbl = new Label(secondaryText);
    subLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B;");
    textBox.getChildren().addAll(nameLbl, subLbl);

    card.getChildren().addAll(avatar, textBox);
    btn.setText("");
    btn.setGraphic(card);
    btn.getStyleClass().setAll("quick-dial-btn");
  }

  private HBox buildTopActionBar(
      Label videoCodecHintLabel,
      Button cameraPreviewBtn,
      Button videoCallBtn,
      Button audioCallBtn,
      Button hangupBtn) {
    HBox topBar = new HBox(10);
    topBar.setAlignment(Pos.CENTER_LEFT);
    topBar.setPadding(new Insets(12, 20, 12, 20));
    topBar.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #E2E8F0; -fx-border-width: 0 0 1 0;");

    VBox titleBox = new VBox(2);
    Label mainTitle = new Label("音视频通信与多方会议工作台");
    mainTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0F172A;");
    videoCodecHintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B;");
    titleBox.getChildren().addAll(mainTitle, videoCodecHintLabel);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    cameraPreviewBtn.getStyleClass().setAll("btn-secondary");
    videoCallBtn.getStyleClass().setAll("btn-call-video");
    audioCallBtn.getStyleClass().setAll("btn-call-audio");
    hangupBtn.getStyleClass().setAll("btn-hangup", "btn-danger");

    topBar.getChildren().addAll(titleBox, spacer, cameraPreviewBtn, videoCallBtn, audioCallBtn, hangupBtn);
    return topBar;
  }

  private void initSettingsModal(
      Stage owner,
      TextField sipServerField,
      Button fillLocalIpBtn,
      TextField httpBackendField,
      Button testBackendBtn,
      Label httpLoginBadgeLabel,
      ComboBox<String> transportBox,
      TextField domainField,
      TextField usernameField,
      PasswordField passwordField,
      TextField localPortField,
      Button startEngineBtn,
      Button registerSipBtn,
      Button loginBackendBtn) {
    settingsModalStage = new Stage();
    settingsModalStage.initOwner(owner);
    settingsModalStage.initModality(Modality.NONE);
    settingsModalStage.setTitle("网络与账号设置 (Kamailio / Spring Boot 后台)");

    VBox root = new VBox(14);
    root.setPadding(new Insets(20));
    root.setStyle("-fx-background-color: #FFFFFF;");

    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(10);

    fillLocalIpBtn.getStyleClass().setAll("btn-secondary");
    testBackendBtn.getStyleClass().setAll("btn-secondary");

    grid.addRow(0, new Label("SIP 服务器:"), sipServerField, fillLocalIpBtn);
    grid.addRow(1, new Label("HTTP 后台:"), httpBackendField, testBackendBtn);
    grid.addRow(2, new Label("传输协议:"), transportBox, new Label("域: lab"));
    grid.addRow(3, new Label("SIP 域:"), domainField);
    grid.addRow(4, new Label("登录用户:"), usernameField);
    grid.addRow(5, new Label("登录密码:"), passwordField);
    grid.addRow(6, new Label("本机 SIP 端口:"), localPortField);
    GridPane.setHgrow(sipServerField, Priority.ALWAYS);
    GridPane.setHgrow(httpBackendField, Priority.ALWAYS);

    HBox actionRow = new HBox(10);
    actionRow.setAlignment(Pos.CENTER_RIGHT);
    startEngineBtn.getStyleClass().setAll("btn-secondary");
    registerSipBtn.getStyleClass().setAll("btn-secondary");
    loginBackendBtn.getStyleClass().setAll("btn-primary");
    actionRow.getChildren().addAll(startEngineBtn, registerSipBtn, loginBackendBtn);

    root.getChildren().addAll(grid, new Separator(), actionRow);
    Scene modalScene = new Scene(root, 480, 380);
    try {
      modalScene.getStylesheets().add(getClass().getResource("/modern-theme.css").toExternalForm());
    } catch (Exception ignored) {
    }
    settingsModalStage.setScene(modalScene);
  }

  void updateOnlineButton(boolean engineStarted, boolean sipRegistered) {
    if (oneClickOnlineBtn == null) return;
    if (sipRegistered) {
      oneClickOnlineBtn.setText("已注册上线 (点击可重连)");
      oneClickOnlineBtn.getStyleClass().setAll("btn-primary");
      oneClickOnlineBtn.setDisable(false);
    } else if (engineStarted) {
      oneClickOnlineBtn.setText("一键上线 (点击注册)");
      oneClickOnlineBtn.getStyleClass().setAll("btn-primary");
      oneClickOnlineBtn.setDisable(false);
    } else {
      oneClickOnlineBtn.setText("一键启动并上线");
      oneClickOnlineBtn.getStyleClass().setAll("btn-primary");
      oneClickOnlineBtn.setDisable(false);
    }
  }

  private void runOneClickConnect(
      Button triggerBtn,
      Label engineStatusLabel,
      Label httpLoginBadgeLabel,
      Button startEngineBtn,
      Button registerSipBtn,
      Button loginBackendBtn) {
    triggerBtn.setDisable(true);
    triggerBtn.setText("正在启动引擎并注册...");

    Task<Void> autoTask =
        new Task<>() {
          @Override
          protected Void call() throws Exception {
            Platform.runLater(
                () -> {
                  if (!startEngineBtn.isDisabled()) {
                    startEngineBtn.fire();
                  }
                });
            Thread.sleep(600);
            Platform.runLater(
                () -> {
                  if (!registerSipBtn.isDisabled()) {
                    registerSipBtn.fire();
                  }
                  if (!loginBackendBtn.isDisabled()) {
                    loginBackendBtn.fire();
                  }
                });
            return null;
          }

          @Override
          protected void succeeded() {
            triggerBtn.setDisable(false);
          }

          @Override
          protected void failed() {
            triggerBtn.setDisable(false);
          }
        };
    Thread t = new Thread(autoTask, "one-click-online");
    t.setDaemon(true);
    t.start();
  }

  private Button createSvgNavBtn(String svgPathContent, String text, boolean active) {
    SVGPath icon = new SVGPath();
    icon.setContent(svgPathContent);
    icon.setFill(Color.TRANSPARENT);
    icon.setStroke(active ? Color.web("#2481CC") : Color.web("#5A6672"));
    icon.setStrokeWidth(1.8);
    Button btn = new Button(text, icon);
    btn.getStyleClass().add(active ? "nav-btn-active" : "nav-btn");
    btn.setPrefWidth(54);
    return btn;
  }
}
