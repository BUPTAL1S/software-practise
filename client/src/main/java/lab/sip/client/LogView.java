package lab.sip.client;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Color-coded SIP and system log console with search filter and pop-out window docking. */
final class LogView extends VBox {
  enum Kind {
    SYS("系统", "#64748B"),
    REG("注册", "#10B981"),
    CALL("通话", "#2481CC"),
    SIP("信令", "#8B5CF6"),
    WARN("警告", "#F59E0B"),
    ERR("错误", "#EF4444");

    final String label;
    final String color;

    Kind(String label, String color) {
      this.label = label;
      this.color = color;
    }

    static Kind fromCategory(String category) {
      if (category == null) {
        return SYS;
      }
      return switch (category) {
        case "注册" -> REG;
        case "通话" -> CALL;
        case "信令" -> SIP;
        case "警告" -> WARN;
        case "错误" -> ERR;
        default -> SYS;
      };
    }
  }

  static final class Row {
    final String time;
    final Kind kind;
    final String text;
    final String detail;
    final boolean outgoing;

    Row(Kind kind, String text, String detail, boolean outgoing) {
      this.time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
      this.kind = kind;
      this.text = text;
      this.detail = detail;
      this.outgoing = outgoing;
    }
  }

  private static final int MAX_ROWS = 500;

  private final ObservableList<Row> rows = FXCollections.observableArrayList();
  private final FilteredList<Row> filtered = new FilteredList<>(rows, r -> true);
  private final ListView<Row> list = new ListView<>(filtered);
  private final TextArea detail = new TextArea();
  private final VBox detailContainer = new VBox(6);
  private final Label detailTitle = new Label();
  private final Button copyBtn = new Button("复制");
  private final Button closeDetailBtn = new Button("关闭");
  private final SplitPane splitPane = new SplitPane();

  private final TextField searchField = new TextField();
  private final Label counterLabel = new Label("共 0 条");
  private final Button detachBtn = new Button("弹出独立窗口");

  private Kind kindFilter;
  private boolean sipOnly;
  private Runnable onCloseDrawer;

  private Stage detachedStage;
  private StackPane dockHost;
  private VBox placeholderPane;
  private Stage ownerStage;

  LogView() {
    getStyleClass().add("console-container");
    setSpacing(8);
    VBox.setVgrow(this, Priority.ALWAYS);

    Button all = new Button("全部");
    Button sip = new Button("信令");
    Button call = new Button("通话");
    Button warn = new Button("警告");
    Button clear = new Button("清空");

    all.setOnAction(e -> setFilter(null, false));
    sip.setOnAction(e -> setFilter(Kind.SIP, true));
    call.setOnAction(e -> setFilter(Kind.CALL, false));
    warn.setOnAction(e -> setFilter(Kind.WARN, false));
    clear.setOnAction(e -> clear());

    Button[] filterButtons = {all, sip, call, warn, clear};
    buildConsoleUi(filterButtons, list, detail);
  }

  private void buildConsoleUi(
      Button[] logFilterButtons, ListView<Row> signalFlowList, TextArea rawSipTextArea) {
    // 1. 顶部工具栏：标题 + 筛选胶囊组 + 关键字搜索框 + 计数标签 + 清空 + 弹出窗口按钮
    HBox header = new HBox(6);
    header.setAlignment(Pos.CENTER_LEFT);

    Label title = new Label("信令与控制台");
    title.setStyle(
        "-fx-text-fill: #1E293B; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 0 4 0 0;");
    title.setMinWidth(Region.USE_PREF_SIZE);
    header.getChildren().add(title);

    for (int i = 0; i < logFilterButtons.length; i++) {
      Button btn = logFilterButtons[i];
      btn.setMinWidth(Region.USE_PREF_SIZE);
      if (btn.getText().contains("清空")) {
        btn.getStyleClass().setAll("drawer-clear-btn");
      } else {
        btn.getStyleClass().setAll(i == 0 ? "drawer-filter-btn-active" : "drawer-filter-btn");
        var originalHandler = btn.getOnAction();
        btn.setOnAction(
            e -> {
              if (originalHandler != null) {
                originalHandler.handle(e);
              }
              for (Button b : logFilterButtons) {
                if (!b.getText().contains("清空")) {
                  b.getStyleClass().setAll("drawer-filter-btn");
                }
              }
              btn.getStyleClass().setAll("drawer-filter-btn-active");
            });
        header.getChildren().add(btn);
      }
    }

    searchField.setPromptText("搜索方法 / 状态码 / 关键字...");
    searchField.getStyleClass().add("table-search-field");
    searchField.setPrefWidth(150);
    searchField.setMinWidth(110);
    searchField.textProperty().addListener((obs, oldV, newV) -> updateFilter());
    header.getChildren().add(searchField);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    counterLabel.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
    counterLabel.setMinWidth(Region.USE_PREF_SIZE);

    Button clearBtn = logFilterButtons[logFilterButtons.length - 1];
    clearBtn.setMinWidth(Region.USE_PREF_SIZE);

    detachBtn.getStyleClass().setAll("drawer-filter-btn");
    detachBtn.setMinWidth(Region.USE_PREF_SIZE);
    detachBtn.setOnAction(e -> toggleDetach());

    header.getChildren().addAll(spacer, counterLabel, clearBtn, detachBtn);

    // 2. 主体区：左侧信号流列表，右侧详细报文 (点击项时按需滑出)
    signalFlowList.setCellFactory(v -> new RowCell());
    signalFlowList.getStyleClass().add("terminal-list-view");
    signalFlowList
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (obs, old, row) -> {
              if (row != null) {
                showDetail(row);
              }
            });
    signalFlowList.setOnMouseClicked(
        e -> {
          Row selected = signalFlowList.getSelectionModel().getSelectedItem();
          if (selected != null) {
            showDetail(selected);
          }
        });

    detailContainer.getStyleClass().add("detail-container");

    HBox detailHeader = new HBox(8);
    detailHeader.setAlignment(Pos.CENTER_LEFT);
    detailHeader.setPadding(new Insets(2, 4, 4, 4));

    detailTitle.setStyle(
        "-fx-text-fill: #2481CC; -fx-font-weight: bold; -fx-font-size: 11.5px;");

    Region detailSpacer = new Region();
    HBox.setHgrow(detailSpacer, Priority.ALWAYS);

    copyBtn.getStyleClass().setAll("drawer-filter-btn");
    copyBtn.setOnAction(
        e -> {
          String text = detail.getText();
          if (text != null && !text.isBlank()) {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text);
            Clipboard.getSystemClipboard().setContent(cc);
            copyBtn.setText("已复制");
            PauseTransition pt = new PauseTransition(Duration.seconds(1.5));
            pt.setOnFinished(ev -> copyBtn.setText("复制"));
            pt.play();
          }
        });

    closeDetailBtn.getStyleClass().setAll("drawer-filter-btn");
    closeDetailBtn.setStyle("-fx-padding: 2 7 2 7; -fx-font-size: 11px;");
    closeDetailBtn.setOnAction(e -> closeDetail());

    detailHeader.getChildren().addAll(detailTitle, detailSpacer, copyBtn, closeDetailBtn);

    rawSipTextArea.setEditable(false);
    rawSipTextArea.setFocusTraversable(false);
    rawSipTextArea.getStyleClass().add("raw-sip-textarea");
    rawSipTextArea.setWrapText(true);
    VBox.setVgrow(rawSipTextArea, Priority.ALWAYS);

    detailContainer.getChildren().addAll(detailHeader, rawSipTextArea);

    splitPane.getStyleClass().add("terminal-split-pane");
    splitPane.getItems().setAll(signalFlowList);
    VBox.setVgrow(splitPane, Priority.ALWAYS);

    getChildren().addAll(header, splitPane);
  }

  void selectRow(int index) {
    if (index >= 0 && index < filtered.size()) {
      list.getSelectionModel().select(index);
    }
  }

  void setOnCloseDrawer(Runnable r) {
    this.onCloseDrawer = r;
  }

  void setOwnerStage(Stage owner) {
    this.ownerStage = owner;
  }

  void setupDockHost(StackPane host) {
    this.dockHost = host;
    initPlaceholder();
  }

  void setupDockHost(StackPane host, Stage owner) {
    this.dockHost = host;
    this.ownerStage = owner;
    initPlaceholder();
  }

  private void initPlaceholder() {
    if (placeholderPane != null) {
      return;
    }
    placeholderPane = new VBox(14);
    placeholderPane.setAlignment(Pos.CENTER);
    placeholderPane.setPadding(new Insets(40));
    placeholderPane.getStyleClass().add("empty-placeholder-card");

    Label phTitle = new Label("SIP 信令与系统控制台正在独立窗口运行");
    phTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1E293B;");

    Label phDesc =
        new Label("控制台已弹出为独立窗口，可拖拽至副屏或与主界面并排显示，便于大屏/多屏实时监控。");
    phDesc.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748B;");

    Button dockBtn = new Button("收回到主窗口");
    dockBtn.getStyleClass().setAll("btn-primary");
    dockBtn.setOnAction(e -> dockBack());

    Button toFrontBtn = new Button("置顶显示独立窗口");
    toFrontBtn.getStyleClass().setAll("btn-secondary");
    toFrontBtn.setOnAction(e -> showDetachedWindow());

    HBox btnBox = new HBox(12, dockBtn, toFrontBtn);
    btnBox.setAlignment(Pos.CENTER);

    placeholderPane.getChildren().addAll(phTitle, phDesc, btnBox);
  }

  void detachToWindow() {
    if (dockHost == null) {
      return;
    }
    if (detachedStage == null) {
      detachedStage = new Stage();
      detachedStage.setTitle("SIP 信令与系统控制台 - 独立窗口");
      if (ownerStage != null) {
        detachedStage.initOwner(ownerStage);
      }
      detachedStage.setOnCloseRequest(e -> dockBack());
    }
    if (dockHost.getChildren().contains(this)) {
      dockHost.getChildren().remove(this);
    }
    initPlaceholder();
    if (!dockHost.getChildren().contains(placeholderPane)) {
      dockHost.getChildren().setAll(placeholderPane);
    }
    BorderPane popRoot = new BorderPane(this);
    popRoot.setPadding(new Insets(10));
    popRoot.setStyle("-fx-background-color: #F8FAFC;");
    Scene popScene = new Scene(popRoot, 960, 620);
    try {
      popScene.getStylesheets().add(getClass().getResource("/modern-theme.css").toExternalForm());
    } catch (Exception ignored) {
    }
    detachedStage.setScene(popScene);
    detachBtn.setText("收回到主窗口");
    detachedStage.show();
    detachedStage.toFront();
  }

  void dockBack() {
    if (detachedStage != null && detachedStage.isShowing()) {
      detachedStage.hide();
    }
    if (dockHost != null) {
      if (placeholderPane != null) {
        dockHost.getChildren().remove(placeholderPane);
      }
      if (!dockHost.getChildren().contains(this)) {
        dockHost.getChildren().setAll(this);
      }
    }
    detachBtn.setText("弹出独立窗口");
  }

  boolean isDetached() {
    return detachedStage != null && detachedStage.isShowing();
  }

  void showDetachedWindow() {
    if (isDetached()) {
      detachedStage.toFront();
    } else {
      detachToWindow();
    }
  }

  private void toggleDetach() {
    if (isDetached()) {
      dockBack();
    } else {
      detachToWindow();
    }
  }

  void appendApp(String msg) {
    append(Kind.SYS, msg, null, false);
  }

  void append(String category, String msg) {
    Kind kind = Kind.fromCategory(category);
    if (kind == Kind.WARN && msg != null && msg.startsWith("错误")) {
      kind = Kind.ERR;
    }
    if ("错误".equals(category)) {
      kind = Kind.ERR;
    }
    append(kind, msg, null, false);
  }

  void appendSip(boolean outgoing, String summary, String raw) {
    String arrow = outgoing ? "-> " : "<- ";
    append(Kind.SIP, arrow + summary, raw, outgoing);
  }

  private void append(Kind kind, String text, String detailText, boolean outgoing) {
    Row row = new Row(kind, text, detailText, outgoing);
    rows.add(row);
    while (rows.size() > MAX_ROWS) {
      rows.remove(0);
    }
    updateCounterLabel();
    list.scrollTo(row);
  }

  private void openDetail() {
    if (!splitPane.getItems().contains(detailContainer)) {
      splitPane.getItems().add(detailContainer);
      splitPane.setDividerPositions(0.58);
    }
  }

  private void closeDetail() {
    splitPane.getItems().remove(detailContainer);
    list.getSelectionModel().clearSelection();
  }

  private void showDetail(Row row) {
    if (row == null) {
      return;
    }
    String contentText;
    if (row.kind == Kind.SIP && row.detail != null && !row.detail.isBlank()) {
      detailTitle.setText("SIP 报文详情 (" + (row.outgoing ? "发送 ->" : "接收 <-") + ")");
      detailTitle.setStyle(
          "-fx-text-fill: #2481CC; -fx-font-weight: bold; -fx-font-size: 11.5px;");
      contentText = row.detail;
    } else {
      detailTitle.setText("[" + row.kind.label + "] 日志详情 (" + row.time + ")");
      detailTitle.setStyle(
          "-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 11.5px;");
      StringBuilder sb = new StringBuilder();
      sb.append("时间: ").append(row.time).append("\n");
      sb.append("级别: ").append(row.kind.label).append("\n");
      sb.append("内容:\n").append(row.text);
      if (row.detail != null && !row.detail.isBlank()) {
        sb.append("\n\n补充信息:\n").append(row.detail);
      }
      contentText = sb.toString();
    }
    detail.setText(contentText);
    openDetail();
  }

  private void updateFilter() {
    String kw =
        (searchField.getText() != null)
            ? searchField.getText().trim().toLowerCase(Locale.ROOT)
            : "";
    filtered.setPredicate(
        row -> {
          boolean kindMatch;
          if (sipOnly) {
            kindMatch = (row.kind == Kind.SIP);
          } else if (kindFilter == null) {
            kindMatch = true;
          } else if (kindFilter == Kind.WARN) {
            kindMatch = (row.kind == Kind.WARN || row.kind == Kind.ERR);
          } else {
            kindMatch = (row.kind == kindFilter);
          }
          if (!kindMatch) {
            return false;
          }
          if (kw.isEmpty()) {
            return true;
          }
          if (row.text != null && row.text.toLowerCase(Locale.ROOT).contains(kw)) {
            return true;
          }
          if (row.detail != null && row.detail.toLowerCase(Locale.ROOT).contains(kw)) {
            return true;
          }
          return false;
        });
    updateCounterLabel();
  }

  private void setFilter(Kind kind, boolean onlySip) {
    this.kindFilter = kind;
    this.sipOnly = onlySip;
    updateFilter();
  }

  private void updateCounterLabel() {
    int cur = filtered.size();
    int total = rows.size();
    if (cur == total) {
      counterLabel.setText("共 " + total + " 条");
    } else {
      counterLabel.setText("过滤显示 " + cur + " / " + total + " 条");
    }
  }

  private void clear() {
    rows.clear();
    detail.clear();
    closeDetail();
    updateCounterLabel();
  }

  private static final class RowCell extends ListCell<Row> {
    private final HBox box = new HBox(8);
    private final Label time = new Label();
    private final Label badge = new Label();
    private final Circle dir = new Circle(3.5);
    private final Label text = new Label();

    RowCell() {
      box.setMouseTransparent(true);
      time.setMinWidth(64);
      time.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");
      badge.setMinWidth(48);
      badge.setAlignment(Pos.CENTER);
      text.setMaxWidth(Double.MAX_VALUE);
      HBox.setHgrow(text, Priority.ALWAYS);
      box.setAlignment(Pos.CENTER_LEFT);
      box.getChildren().addAll(time, badge, dir, text);
    }

    @Override
    protected void updateItem(Row row, boolean empty) {
      super.updateItem(row, empty);
      if (empty || row == null) {
        setGraphic(null);
        setStyle("-fx-background-color: transparent;");
        return;
      }
      setStyle("-fx-background-color: transparent;");
      time.setText(row.time);
      badge.setText(row.kind.label);
      badge.setStyle(
          "-fx-background-color: "
              + row.kind.color
              + "; -fx-text-fill: white; -fx-padding: 1 5 1 5; -fx-background-radius: 4;"
              + " -fx-font-size: 10px; -fx-font-weight: bold;");
      dir.setFill(
          row.kind == Kind.SIP
              ? Color.web(row.outgoing ? "#2481CC" : "#10B981")
              : Color.TRANSPARENT);
      text.setText(row.text);
      if (row.kind == Kind.ERR) {
        text.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 11.5px; -fx-font-weight: bold;");
      } else if (row.kind == Kind.WARN) {
        text.setStyle("-fx-text-fill: #D97706; -fx-font-size: 11.5px;");
      } else if (row.kind == Kind.SIP) {
        text.setStyle("-fx-text-fill: #0F172A; -fx-font-size: 11.5px; -fx-font-weight: 500;");
      } else {
        text.setStyle("-fx-text-fill: #475569; -fx-font-size: 11.5px;");
      }
      setGraphic(box);
    }
  }
}
