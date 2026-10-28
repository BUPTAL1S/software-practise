package lab.sip.client;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
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
import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import java.io.File;

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
  private VBox quickList;
  private TextField contactTargetField;
  private String directorySignature = "";

  private Runnable onAvatarClicked;
  private final StackPane globalAvatarPane = new StackPane();
  private final ImageView globalAvatarImgView = new ImageView();
  private final Circle globalAvatarBg = new Circle(18, Color.web("#2481CC"));
  private final Label globalAvatarUserLbl = new Label();
  private final Circle globalAvatarRing = new Circle(18, Color.TRANSPARENT);

  public void setOnAvatarClicked(Runnable onAvatarClicked) {
    this.onAvatarClicked = onAvatarClicked;
  }

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
    return assembleProductUi(
        primaryStage,
        engineStatusLabel,
        videoCodecHintLabel,
        httpLoginBadgeLabel,
        callTargetField,
        quickDialButtons,
        videoCallBtn,
        audioCallBtn,
        hangupBtn,
        cameraPreviewBtn,
        null,
        incomingBox,
        sipServerField,
        fillLocalIpBtn,
        httpBackendField,
        testBackendBtn,
        transportBox,
        domainField,
        usernameField,
        passwordField,
        localPortField,
        startEngineBtn,
        registerSipBtn,
        loginBackendBtn,
        logView,
        mainTabPane);
  }

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
      CheckBox cameraToggleBox,
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
            cameraToggleBox,
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

    // Avatar (Interactive - click to customize avatar and profile)
    globalAvatarImgView.setFitWidth(36);
    globalAvatarImgView.setFitHeight(36);
    globalAvatarImgView.setClip(new Circle(18, 18, 18));
    globalAvatarImgView.setVisible(false);

    globalAvatarRing.setStroke(Color.web("#E2E8F0"));
    globalAvatarRing.setStrokeWidth(1.5);

    String initial = usernameField.getText();
    globalAvatarUserLbl.setText((initial != null && initial.length() > 4) ? initial.substring(0, 4) : (initial != null ? initial : ""));
    usernameField.textProperty().addListener((obs, oldV, newV) -> {
      String n = newV != null ? newV.trim() : "";
      globalAvatarUserLbl.setText(n.length() > 4 ? n.substring(0, 4) : n);
    });
    globalAvatarUserLbl.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;");

    globalAvatarPane.getChildren().setAll(globalAvatarBg, globalAvatarUserLbl, globalAvatarImgView, globalAvatarRing);
    globalAvatarPane.setCursor(Cursor.HAND);
    Tooltip.install(globalAvatarPane, new Tooltip("点击修改个人资料与头像 (开会关闭摄像头将以此头像展示)"));
    globalAvatarPane.setOnMouseClicked(
        e -> {
          if (onAvatarClicked != null) {
            onAvatarClicked.run();
          }
        });

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

    rail.getChildren().addAll(globalAvatarPane, msgBtn, mcuBtn, cdrBtn, spacer, consoleBtn, configBtn);
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

    // Status capsules (Equal 50% split, centered)
    HBox badgeRow = new HBox(8);
    badgeRow.setAlignment(Pos.CENTER);
    badgeRow.setMaxWidth(Double.MAX_VALUE);

    engineStatusLabel.getStyleClass().setAll("badge-offline");
    httpLoginBadgeLabel.getStyleClass().setAll("badge-warn");

    engineStatusLabel.setMaxWidth(Double.MAX_VALUE);
    httpLoginBadgeLabel.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(engineStatusLabel, Priority.ALWAYS);
    HBox.setHgrow(httpLoginBadgeLabel, Priority.ALWAYS);

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
    this.contactTargetField = callTargetField;
    this.quickList = new VBox(6);
    if (quickDialButtons.length == 0) {
      Label loading = new Label("正在加载通讯录...");
      loading.setStyle("-fx-font-size: 11px; -fx-text-fill: #94A3B8; -fx-padding: 4 2 4 2;");
      quickList.getChildren().add(loading);
    }
    for (Button btn : quickDialButtons) {
      formatContactButton(btn);
      btn.setMaxWidth(Double.MAX_VALUE);
      quickList.getChildren().add(btn);
    }
    ScrollPane quickScroll = new ScrollPane(quickList);
    quickScroll.setFitToWidth(true);
    quickScroll.setPannable(true);
    quickScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    quickScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    quickScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-padding: 0;");
    quickScroll.setMinHeight(120);
    VBox.setVgrow(quickScroll, Priority.ALWAYS);

    sidebar.getChildren().addAll(
        badgeRow,
        oneClickOnlineBtn,
        openConfigLink,
        new Separator(),
        dialTitle,
        callTargetField,
        quickTitle,
        quickScroll);
    return sidebar;
  }

  static final class ContactCardHandle {
    final String targetId;
    final Label nameLabel;
    final Label subLabel;
    final Circle statusDot;
    final Label unreadBadge;
    final String basePrefix;
    final ImageView contactImgView;
    final Label badgeLbl;

    ContactCardHandle(
        String targetId,
        Label nameLabel,
        Label subLabel,
        Circle statusDot,
        Label unreadBadge,
        String basePrefix,
        ImageView contactImgView,
        Label badgeLbl) {
      this.targetId = targetId;
      this.nameLabel = nameLabel;
      this.subLabel = subLabel;
      this.statusDot = statusDot;
      this.unreadBadge = unreadBadge;
      this.basePrefix = basePrefix;
      this.contactImgView = contactImgView;
      this.badgeLbl = badgeLbl;
    }
  }

  private final java.util.Map<String, ContactCardHandle> contactCardMap =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.Map<String, Integer> contactUnreadMap =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.Map<String, Boolean> contactOnlineMap =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.Map<String, String> contactAvatarMap =
      new java.util.concurrent.ConcurrentHashMap<>();

  public java.util.Set<String> getContactTargetIds() {
    return java.util.Collections.unmodifiableSet(contactCardMap.keySet());
  }

  public ContactCardHandle getContactCard(String targetId) {
    if (targetId == null) return null;
    return contactCardMap.get(targetId.trim().toLowerCase());
  }

  public int getContactUnread(String targetId) {
    if (targetId == null) return 0;
    return contactUnreadMap.getOrDefault(targetId.trim().toLowerCase(), 0);
  }

  public void updateContactStatus(String targetId, boolean online) {
    if (targetId == null) return;
    contactOnlineMap.put(targetId.trim().toLowerCase(), online);
    Runnable r = () -> {
      ContactCardHandle card = contactCardMap.get(targetId.trim().toLowerCase());
      if (card != null) {
        if (card.targetId.startsWith("room")) {
          card.subLabel.setText(card.basePrefix + " · " + (online ? "就绪(多方共享)" : "未就绪(MCU离线)"));
          card.subLabel.setStyle(
              online
                  ? "-fx-font-size: 11px; -fx-text-fill: #10B981;"
                  : "-fx-font-size: 11px; -fx-text-fill: #94A3B8;");
          card.statusDot.setFill(Color.web(online ? "#10B981" : "#94A3B8"));
        } else {
          card.subLabel.setText(card.basePrefix + " · " + (online ? "在线" : "离线"));
          card.subLabel.setStyle(
              online
                  ? "-fx-font-size: 11px; -fx-text-fill: #10B981;"
                  : "-fx-font-size: 11px; -fx-text-fill: #94A3B8;");
          card.statusDot.setFill(Color.web(online ? "#10B981" : "#94A3B8"));
        }
      }
    };
    if (Platform.isFxApplicationThread()) {
      r.run();
    } else {
      Platform.runLater(r);
    }
  }

  public void updateContactUnread(String targetId, int count) {
    if (targetId == null) return;
    String key = targetId.trim().toLowerCase();
    contactUnreadMap.put(key, Math.max(0, count));
    Runnable r = () -> {
      ContactCardHandle card = contactCardMap.get(key);
      if (card != null) {
        if (count > 0) {
          card.unreadBadge.setText(count > 99 ? "99+" : String.valueOf(count));
          card.unreadBadge.setVisible(true);
          card.unreadBadge.setManaged(true);
        } else {
          card.unreadBadge.setText("");
          card.unreadBadge.setVisible(false);
          card.unreadBadge.setManaged(false);
        }
      }
    };
    if (Platform.isFxApplicationThread()) {
      r.run();
    } else {
      Platform.runLater(r);
    }
  }

  /**
   * Rebuilds the left contact/room list from the backend directory.
   * Skips the rebuild when the directory contents are unchanged so periodic
   * polling does not steal clicks or flicker the list.
   */
  void rebuildDirectory(
      java.util.List<HttpFileClient.UserSummary> users,
      java.util.List<HttpFileClient.RoomInfo> rooms,
      String self,
      String httpBaseUrl) {
    if (users == null || quickList == null) {
      return;
    }
    String me = self == null ? "" : self.trim();
    java.util.List<HttpFileClient.RoomInfo> safeRooms =
        rooms == null ? java.util.List.of() : rooms;

    StringBuilder signature = new StringBuilder();
    signature.append(me).append('|');
    for (HttpFileClient.UserSummary user : users) {
      if (user == null) continue;
      signature.append(user.username()).append('/').append(user.sipId()).append('/')
          .append(user.avatarUrl()).append('/').append(user.nickname()).append(';');
    }
    signature.append('|');
    for (HttpFileClient.RoomInfo room : safeRooms) {
      if (room == null) continue;
      signature.append(room.sipUri()).append('/').append(room.name()).append(';');
    }
    if (signature.toString().equals(directorySignature)) {
      return;
    }
    directorySignature = signature.toString();

    Runnable rebuild = () -> {
      quickList.getChildren().clear();
      contactCardMap.clear();

      quickList.getChildren().add(directorySectionLabel("联系人"));
      java.util.Set<String> seen = new java.util.HashSet<>();
      boolean hasUser = false;
      for (HttpFileClient.UserSummary user : users) {
        if (user == null) continue;
        String target = firstNonBlank(user.username(), user.sipId());
        if (target.isEmpty() || target.equalsIgnoreCase(me) || !seen.add(target.toLowerCase())) {
          continue;
        }
        String avatar = user.avatarUrl();
        if (avatar != null && !avatar.isBlank() && httpBaseUrl != null
            && !avatar.startsWith("http://") && !avatar.startsWith("https://")) {
          avatar = httpBaseUrl.replaceAll("/+$", "") + "/" + avatar.replaceAll("^/+", "");
        }
        if (avatar != null && !avatar.isBlank()) {
          contactAvatarMap.put(target.toLowerCase(), avatar);
        }
        Button btn = new Button();
        installContactCard(
            btn, target, firstNonBlank(user.nickname(), target), target, badgeFor(target), colorFor(target), false);
        btn.setMaxWidth(Double.MAX_VALUE);
        quickList.getChildren().add(btn);
        hasUser = true;
      }
      if (!hasUser) {
        quickList.getChildren().add(emptyDirectoryLabel("暂无联系人"));
      }

      quickList.getChildren().add(directorySectionLabel("研讨室"));
      boolean hasRoom = false;
      for (HttpFileClient.RoomInfo room : safeRooms) {
        if (room == null) continue;
        String target = firstNonBlank(room.sipUri());
        if (target.isEmpty() || !seen.add(target.toLowerCase())) {
          continue;
        }
        String display = firstNonBlank(room.name(), target);
        Button btn = new Button();
        installContactCard(btn, target, display, target, "M", "#F59E0B", true);
        btn.setMaxWidth(Double.MAX_VALUE);
        quickList.getChildren().add(btn);
        hasRoom = true;
      }
      if (!hasRoom) {
        quickList.getChildren().add(emptyDirectoryLabel("暂无研讨室"));
      }
    };
    if (Platform.isFxApplicationThread()) {
      rebuild.run();
    } else {
      Platform.runLater(rebuild);
    }
  }

  private static Label directorySectionLabel(String text) {
    Label label = new Label(text);
    label.setStyle(
        "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #64748B;"
            + " -fx-padding: 7 2 2 2;");
    return label;
  }

  private static Label emptyDirectoryLabel(String text) {
    Label label = new Label(text);
    label.setStyle("-fx-font-size: 11px; -fx-text-fill: #94A3B8; -fx-padding: 4 2 4 2;");
    return label;
  }

  private static String firstNonBlank(String... values) {
    if (values != null) {
      for (String value : values) {
        if (value != null && !value.isBlank()) {
          return value.trim();
        }
      }
    }
    return "";
  }

  private static String badgeFor(String target) {
    if (target == null || target.isBlank()) {
      return "?";
    }
    String value = target.trim();
    if (value.regionMatches(true, 0, "room", 0, 4)) {
      return "M";
    }
    char last = value.charAt(value.length() - 1);
    return Character.isDigit(last)
        ? String.valueOf(last)
        : value.substring(0, 1).toUpperCase();
  }

  private static String colorFor(String target) {
    String[] colors = {"#2481CC", "#10B981", "#8B5CF6", "#F59E0B", "#EF4444", "#0EA5E9"};
    int hash = target == null ? 0 : target.hashCode();
    return colors[(hash & 0x7fffffff) % colors.length];
  }

  /**
   * Installs the Telegram-style card graphic into an existing button and
   * registers its handle. The caller decides whether to add the button to the
   * quick list.
   */
  private ContactCardHandle installContactCard(
      Button btn,
      String targetId,
      String primaryText,
      String basePrefix,
      String badgeText,
      String badgeColor,
      boolean isRoom) {
    HBox card = new HBox(8);
    card.setAlignment(Pos.CENTER_LEFT);

    StackPane avatar = new StackPane();
    Circle circle = new Circle(15, Color.web(badgeColor));
    Label badgeLbl = new Label(badgeText);
    badgeLbl.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;");

    ImageView contactImgView = new ImageView();
    contactImgView.setFitWidth(30);
    contactImgView.setFitHeight(30);
    contactImgView.setClip(new Circle(15, 15, 15));
    contactImgView.setVisible(false);

    String key = targetId.trim().toLowerCase();
    boolean online = contactOnlineMap.getOrDefault(key, isRoom);
    Circle statusDot = new Circle(4.5, Color.web(online ? "#10B981" : "#94A3B8"));
    statusDot.setStroke(Color.WHITE);
    statusDot.setStrokeWidth(1.5);
    StackPane.setAlignment(statusDot, Pos.BOTTOM_RIGHT);

    avatar.getChildren().addAll(circle, badgeLbl, contactImgView, statusDot);

    VBox textBox = new VBox(2);
    Label nameLbl = new Label(primaryText);
    nameLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12.5px; -fx-text-fill: #1E293B;");
    String secondary =
        isRoom ? (online ? "就绪(多方共享)" : "未就绪(MCU离线)") : (online ? "在线" : "离线");
    Label subLbl = new Label(basePrefix + " · " + secondary);
    subLbl.setStyle(
        online
            ? "-fx-font-size: 11px; -fx-text-fill: #10B981;"
            : "-fx-font-size: 11px; -fx-text-fill: #94A3B8;");
    textBox.getChildren().addAll(nameLbl, subLbl);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    int existingUnread = contactUnreadMap.getOrDefault(key, 0);
    Label unreadBadge =
        new Label(existingUnread > 99 ? "99+" : (existingUnread > 0 ? String.valueOf(existingUnread) : ""));
    unreadBadge.setStyle(
        "-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-size: 10px;"
            + " -fx-font-weight: bold; -fx-padding: 1 6 1 6; -fx-background-radius: 10;");
    unreadBadge.setVisible(existingUnread > 0);
    unreadBadge.setManaged(existingUnread > 0);

    card.getChildren().addAll(avatar, textBox, spacer, unreadBadge);
    btn.setText("");
    btn.setGraphic(card);
    btn.getStyleClass().setAll("quick-dial-btn");
    btn.setOnAction(
        e -> {
          if (contactTargetField != null) {
            contactTargetField.setText(targetId);
          }
        });

    ContactCardHandle handle =
        new ContactCardHandle(
            targetId, nameLbl, subLbl, statusDot, unreadBadge, basePrefix, contactImgView, badgeLbl);
    contactCardMap.put(key, handle);
    String cachedAvatar = contactAvatarMap.get(key);
    if (cachedAvatar != null && !cachedAvatar.isBlank()) {
      updateContactAvatar(targetId, cachedAvatar);
    }
    return handle;
  }

  void formatContactButton(Button btn) {
    String original = btn.getText();
    if (original == null) {
      original = "";
    }
    String targetId = original.replaceAll("\\s*\\(.*\\)$", "").trim();
    if (targetId.isEmpty()) {
      return;
    }
    boolean isRoom = targetId.toLowerCase().startsWith("room");
    installContactCard(
        btn,
        targetId,
        targetId,
        isRoom ? "MCU 研讨室" : "联系人",
        isRoom ? "M" : badgeFor(targetId),
        isRoom ? "#F59E0B" : colorFor(targetId),
        isRoom);
  }


  private HBox buildTopActionBar(
      Label videoCodecHintLabel,
      CheckBox cameraToggleBox,
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

    HBox actionBox = new HBox(8);
    actionBox.setAlignment(Pos.CENTER_RIGHT);

    cameraPreviewBtn.getStyleClass().setAll("btn-secondary");
    videoCallBtn.getStyleClass().setAll("btn-call-video");
    audioCallBtn.getStyleClass().setAll("btn-call-audio");
    hangupBtn.getStyleClass().setAll("btn-hangup", "btn-danger");

    if (cameraToggleBox != null) {
      cameraToggleBox.setMinWidth(Region.USE_PREF_SIZE);
      actionBox.getChildren().add(cameraToggleBox);
    }
    cameraPreviewBtn.setMinWidth(Region.USE_PREF_SIZE);
    videoCallBtn.setMinWidth(Region.USE_PREF_SIZE);
    audioCallBtn.setMinWidth(Region.USE_PREF_SIZE);
    hangupBtn.setMinWidth(Region.USE_PREF_SIZE);

    actionBox.getChildren().addAll(cameraPreviewBtn, videoCallBtn, audioCallBtn, hangupBtn);

    topBar.getChildren().addAll(titleBox, spacer, actionBox);
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

  public void updateNavAvatar(String avatarUrl, String username) {
    Platform.runLater(
        () -> {
          if (avatarUrl != null && !avatarUrl.isBlank()) {
            try {
              Image img = new Image(avatarUrl, true);
              img.errorProperty()
                  .addListener(
                      (obs, oldV, err) -> {
                        if (err) {
                          globalAvatarImgView.setVisible(false);
                          globalAvatarUserLbl.setVisible(true);
                        }
                      });
              globalAvatarImgView.setImage(img);
              globalAvatarImgView.setVisible(true);
              globalAvatarUserLbl.setVisible(false);
              return;
            } catch (Throwable ignored) {
            }
          }
          globalAvatarImgView.setVisible(false);
          globalAvatarUserLbl.setVisible(true);
          String name = (username != null && !username.isBlank()) ? username : "?";
          globalAvatarUserLbl.setText(name.length() > 4 ? name.substring(0, 4) : name);
        });
  }

  public void updateContactAvatar(String targetId, String avatarUrl) {
    if (targetId == null) return;
    String key = targetId.trim().toLowerCase();
    if (avatarUrl == null || avatarUrl.isBlank()) {
      contactAvatarMap.remove(key);
    } else {
      contactAvatarMap.put(key, avatarUrl);
    }
    Platform.runLater(
        () -> {
          ContactCardHandle card = contactCardMap.get(key);
          if (card != null && card.contactImgView != null) {
            if (avatarUrl != null && !avatarUrl.isBlank()) {
              try {
                Image img = new Image(avatarUrl, true);
                img.errorProperty()
                    .addListener(
                        (obs, oldV, err) -> {
                          if (err) {
                            card.contactImgView.setVisible(false);
                            card.badgeLbl.setVisible(true);
                          }
                        });
                card.contactImgView.setImage(img);
                card.contactImgView.setVisible(true);
                card.badgeLbl.setVisible(false);
                return;
              } catch (Throwable ignored) {
              }
            }
            card.contactImgView.setVisible(false);
            card.badgeLbl.setVisible(true);
          }
        });
  }

  public void showAvatarSettingsModal(
      Stage owner,
      HttpFileClient httpClient,
      String username,
      String sipId,
      LogView logs) {
    Stage modal = new Stage();
    if (owner != null) {
      modal.initOwner(owner);
      modal.initModality(Modality.APPLICATION_MODAL);
    }
    modal.setTitle("个人资料与头像设置");

    VBox root = new VBox(16);
    root.setPadding(new Insets(24));
    root.setStyle("-fx-background-color: #FFFFFF;");
    root.setAlignment(Pos.TOP_CENTER);

    Label titleLbl = new Label("个人资料与头像设置");
    titleLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1E293B;");

    Label subtitleLbl =
        new Label("自定义您的显示头像。进入多方会议室关闭摄像头时，MCU 混流画布将以此头像呈现给全体参会者（微信同款）。");
    subtitleLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #64748B; -fx-line-spacing: 2px;");
    subtitleLbl.setWrapText(true);
    subtitleLbl.setMaxWidth(400);

    // Avatar preview box
    StackPane previewBox = new StackPane();
    previewBox.setPrefSize(100, 100);
    previewBox.setMaxSize(100, 100);

    Circle pBg = new Circle(48, Color.web("#2481CC"));
    String initialText = (username != null && !username.isBlank()) ? username : "?";
    if (initialText.length() > 4) initialText = initialText.substring(0, 4);
    Label pInitial = new Label(initialText);
    pInitial.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: white;");

    ImageView pImgView = new ImageView();
    pImgView.setFitWidth(96);
    pImgView.setFitHeight(96);
    pImgView.setPreserveRatio(true);
    pImgView.setSmooth(true);
    pImgView.setClip(new Circle(48, 48, 48));
    pImgView.setVisible(false);

    Circle pRing = new Circle(48, Color.TRANSPARENT);
    pRing.setStroke(Color.web("#38BDF8"));
    pRing.setStrokeWidth(2.5);

    String currentUrl = (httpClient != null) ? httpClient.getUserAvatarUrl() : null;
    if (currentUrl != null && !currentUrl.isBlank() && httpClient != null) {
      try {
        Image initialImg = new Image(httpClient.getBaseUrl() + currentUrl, false);
        initialImg.errorProperty()
            .addListener(
                (obs, oldV, err) -> {
                  if (err) {
                    pImgView.setVisible(false);
                    pInitial.setVisible(true);
                  }
                });
        pImgView.setImage(initialImg);
        pImgView.setVisible(true);
        pInitial.setVisible(false);
      } catch (Throwable ignored) {
      }
    }

    previewBox.getChildren().addAll(pBg, pInitial, pImgView, pRing);

    // File action buttons
    final java.nio.file.Path[] chosenPath = new java.nio.file.Path[1];
    final File[] originalPickedFile = new File[1];
    Label statusLbl = new Label("支持 JPG / PNG / WEBP 等常用格式 (支持交互裁切)");
    statusLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #94A3B8;");

    Button chooseBtn = new Button("选择本地图片文件...");
    chooseBtn.getStyleClass().add("btn-secondary");

    Button recropBtn = new Button("调整裁切...");
    recropBtn.getStyleClass().add("btn-secondary");
    recropBtn.setVisible(false);
    recropBtn.setManaged(false);

    Button resetBtn = new Button("恢复默认头像");
    resetBtn.getStyleClass().add("btn-secondary");

    HBox chooseBox = new HBox(8);
    chooseBox.setAlignment(Pos.CENTER);
    chooseBox.getChildren().addAll(chooseBtn, recropBtn, resetBtn);

    Button saveBtn = new Button("保存并同步至会议室");
    saveBtn.getStyleClass().add("btn-primary");
    saveBtn.setDisable(true);

    chooseBtn.setOnAction(
        e -> {
          FileChooser fc = new FileChooser();
          fc.setTitle("选择头像图片");
          fc.getExtensionFilters()
              .add(
                  new FileChooser.ExtensionFilter(
                      "图像文件 (*.png, *.jpg, *.jpeg, *.webp)",
                      "*.png",
                      "*.jpg",
                      "*.jpeg",
                      "*.webp",
                      "*.PNG",
                      "*.JPG",
                      "*.JPEG",
                      "*.WEBP"));
          File f = fc.showOpenDialog(modal);
          if (f != null) {
            originalPickedFile[0] = f;
            java.nio.file.Path cropped = AvatarCropperDialog.show(modal, f);
            if (cropped != null) {
              chosenPath[0] = cropped;
              Image localImg = new Image(cropped.toUri().toString());
              pImgView.setImage(localImg);
              pImgView.setVisible(true);
              pInitial.setVisible(false);
              statusLbl.setText("已完成 1:1 裁切: " + f.getName());
              statusLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #2563EB; -fx-font-weight: bold;");
              saveBtn.setDisable(false);
              recropBtn.setVisible(true);
              recropBtn.setManaged(true);
            }
          }
        });

    recropBtn.setOnAction(
        e -> {
          if (originalPickedFile[0] != null && originalPickedFile[0].exists()) {
            java.nio.file.Path cropped = AvatarCropperDialog.show(modal, originalPickedFile[0]);
            if (cropped != null) {
              chosenPath[0] = cropped;
              Image localImg = new Image(cropped.toUri().toString());
              pImgView.setImage(localImg);
              pImgView.setVisible(true);
              pInitial.setVisible(false);
              statusLbl.setText("已重新裁切: " + originalPickedFile[0].getName());
              statusLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #2563EB; -fx-font-weight: bold;");
              saveBtn.setDisable(false);
            }
          }
        });

    resetBtn.setOnAction(
        e -> {
          resetBtn.setDisable(true);
          recropBtn.setVisible(false);
          recropBtn.setManaged(false);
          originalPickedFile[0] = null;
          new Thread(
                  () -> {
                    try {
                      if (httpClient != null) {
                        httpClient.resetAvatar(username);
                      }
                      Platform.runLater(
                          () -> {
                            chosenPath[0] = null;
                            pImgView.setImage(null);
                            pImgView.setVisible(false);
                            pInitial.setVisible(true);
                            statusLbl.setText("已恢复为系统占位头像");
                            statusLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #10B981;");
                            saveBtn.setDisable(true);
                            resetBtn.setDisable(false);
                            updateNavAvatar(null, username);
                            updateContactAvatar(username, null);
                            if (logs != null) {
                              logs.append("系统", "已将个人头像恢复为系统占位头像并同步至 FreeSWITCH MCU 会议系统");
                            }
                          });
                    } catch (Exception ex) {
                      Platform.runLater(
                          () -> {
                            statusLbl.setText("恢复失败: " + ex.getMessage());
                            statusLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #EF4444;");
                            resetBtn.setDisable(false);
                          });
                    }
                  })
              .start();
        });

    saveBtn.setOnAction(
        e -> {
          if (chosenPath[0] == null) return;
          saveBtn.setDisable(true);
          saveBtn.setText("正在上传并同步...");
          new Thread(
                  () -> {
                    try {
                      String newUrl = "";
                      if (httpClient != null) {
                        newUrl = httpClient.uploadAvatar(chosenPath[0], username);
                      }
                      final String finalUrl = newUrl;
                      Platform.runLater(
                          () -> {
                            saveBtn.setText("保存并同步至会议室");
                            if (httpClient != null && finalUrl != null && !finalUrl.isBlank()) {
                              updateNavAvatar(httpClient.getBaseUrl() + finalUrl, username);
                              updateContactAvatar(username, httpClient.getBaseUrl() + finalUrl);
                            }
                            if (logs != null) {
                              logs.append("系统", "个人头像已成功上传并同步至 FreeSWITCH MCU 会议系统");
                            }
                            modal.close();
                          });
                    } catch (Exception ex) {
                      Platform.runLater(
                          () -> {
                            saveBtn.setText("保存并同步至会议室");
                            saveBtn.setDisable(false);
                            statusLbl.setText("上传失败: " + ex.getMessage());
                            statusLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #EF4444;");
                          });
                    }
                  })
              .start();
        });

    // Info grid
    GridPane infoGrid = new GridPane();
    infoGrid.setHgap(10);
    infoGrid.setVgap(6);
    infoGrid.setPadding(new Insets(10, 14, 10, 14));
    infoGrid.setStyle(
        "-fx-background-color: #F8FAFC; -fx-background-radius: 8; -fx-border-color: #E2E8F0; -fx-border-radius: 8;");

    Label l1 = new Label("当前账号:");
    l1.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #64748B;");
    Label v1 = new Label(username != null ? username : "");
    v1.setStyle("-fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: #1E293B;");

    Label l2 = new Label("SIP 标识:");
    l2.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #64748B;");
    Label v2 = new Label(sipId != null ? sipId : "");
    v2.setStyle("-fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: #1E293B;");

    Label l3 = new Label("混流映射:");
    l3.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #64748B;");
    Label v3 = new Label("FreeSWITCH 3×3 视频合流头像已就绪");
    v3.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #10B981; -fx-font-weight: bold;");

    infoGrid.add(l1, 0, 0);
    infoGrid.add(v1, 1, 0);
    infoGrid.add(l2, 0, 1);
    infoGrid.add(v2, 1, 1);
    infoGrid.add(l3, 0, 2);
    infoGrid.add(v3, 1, 2);

    Button cancelBtn = new Button("关闭");
    cancelBtn.getStyleClass().add("btn-secondary");
    cancelBtn.setOnAction(e -> modal.close());

    HBox btnBar = new HBox(10);
    btnBar.setAlignment(Pos.CENTER_RIGHT);
    btnBar.getChildren().addAll(cancelBtn, saveBtn);

    root.getChildren()
        .addAll(
            titleLbl,
            subtitleLbl,
            previewBox,
            chooseBox,
            statusLbl,
            infoGrid,
            new Separator(),
            btnBar);

    Scene modalScene = new Scene(root, 440, 480);
    try {
      modalScene.getStylesheets().add(getClass().getResource("/modern-theme.css").toExternalForm());
    } catch (Exception ignored) {
    }
    modal.setScene(modalScene);
    modal.show();
  }
}
