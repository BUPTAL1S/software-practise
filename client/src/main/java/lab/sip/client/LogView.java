package lab.sip.client;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;

/** Color-coded SIP / app log with a compact signaling strip. */
final class LogView extends VBox {
  enum Kind {
    SYS("系统", "#57606a"),
    REG("注册", "#1a7f37"),
    CALL("通话", "#1f6feb"),
    SIP("信令", "#6f42c1"),
    WARN("警告", "#d35400"),
    ERR("错误", "#c0392b");

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

  private static final int MAX_ROWS = 400;
  private static final int MAX_CHIPS = 24;
  private static final DateTimeFormatter CHIP_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  private final ObservableList<Row> rows = FXCollections.observableArrayList();
  private final FilteredList<Row> filtered = new FilteredList<>(rows, r -> true);
  private final ListView<Row> list = new ListView<>(filtered);
  private final TextArea detail = new TextArea();
  private final FlowPane chips = new FlowPane();
  private final Label emptyHint = new Label("信令流会显示在这里：REGISTER / INVITE / 180 / 200 / ACK / BYE");
  private Kind kindFilter;
  private boolean sipOnly;

  LogView() {
    setSpacing(6);
    chips.setHgap(6);
    chips.setVgap(6);
    chips.setPrefWrapLength(680);
    emptyHint.setStyle("-fx-text-fill: #8c959f;");
    chips.getChildren().add(emptyHint);

    ToggleGroup group = new ToggleGroup();
    ToggleButton all = filterBtn("全部", group, true);
    ToggleButton call = filterBtn("通话", group, false);
    ToggleButton sip = filterBtn("信令", group, false);
    ToggleButton warn = filterBtn("警告", group, false);
    all.setOnAction(e -> setFilter(null, false));
    call.setOnAction(e -> setFilter(Kind.CALL, false));
    sip.setOnAction(e -> setFilter(Kind.SIP, true));
    warn.setOnAction(e -> setFilter(Kind.WARN, false));

    Button clear = new Button("清空");
    clear.setOnAction(e -> clear());

    HBox toolbar = new HBox(8, all, call, sip, warn, clear);
    toolbar.setAlignment(Pos.CENTER_LEFT);

    list.setCellFactory(v -> new RowCell());
    list.setPrefHeight(220);
    list.getSelectionModel()
        .selectedItemProperty()
        .addListener((obs, old, row) -> showDetail(row));

    detail.setEditable(false);
    detail.setPrefRowCount(8);
    detail.setWrapText(true);
    detail.setPromptText("点一条「信令」看 SIP 原文");
    detail.setFont(Font.font("Consolas", 12));
    detail.setStyle("-fx-font-family: 'Consolas', 'Cascadia Mono', monospace;");

    Label stripTitle = new Label("信令流");
    stripTitle.setStyle("-fx-font-weight: bold;");
    VBox strip = new VBox(4, stripTitle, chips);
    strip.setPadding(new Insets(4, 0, 2, 0));

    BorderPane body = new BorderPane();
    body.setCenter(list);
    body.setBottom(detail);
    BorderPane.setMargin(detail, new Insets(6, 0, 0, 0));
    VBox.setVgrow(body, Priority.ALWAYS);
    VBox.setVgrow(list, Priority.ALWAYS);

    getChildren().addAll(toolbar, strip, body);
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
    String arrow = outgoing ? "→ " : "← ";
    append(Kind.SIP, arrow + summary, raw, outgoing);
    addChip(outgoing, summary);
  }

  private void append(Kind kind, String text, String detailText, boolean outgoing) {
    Row row = new Row(kind, text, detailText, outgoing);
    rows.add(row);
    while (rows.size() > MAX_ROWS) {
      rows.remove(0);
    }
    list.scrollTo(row);
  }

  private void addChip(boolean outgoing, String summary) {
    if (chips.getChildren().size() == 1 && chips.getChildren().get(0) == emptyHint) {
      chips.getChildren().clear();
    }
    String label = chipLabel(summary);
    Label chip = new Label((outgoing ? "→" : "←") + label);
    chip.setTooltip(new Tooltip(LocalTime.now().format(CHIP_TIME) + "  " + summary));
    String bg = chipColor(label);
    chip.setStyle(
        "-fx-background-color: "
            + bg
            + "; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 10;"
            + " -fx-font-size: 11px;");
    chips.getChildren().add(chip);
    while (chips.getChildren().size() > MAX_CHIPS) {
      chips.getChildren().remove(0);
    }
  }

  private static String chipLabel(String summary) {
    if (summary == null || summary.isBlank()) {
      return "?";
    }
    String line = summary.trim();
    if (line.startsWith("SIP/2.0")) {
      String[] p = line.split("\\s+");
      return p.length > 1 ? p[1] : line;
    }
    return line.split("\\s+")[0];
  }

  private static String chipColor(String label) {
    if (label.matches("\\d+")) {
      int code = Integer.parseInt(label);
      if (code >= 200 && code < 300) {
        return "#1a7f37";
      }
      if (code >= 100 && code < 200) {
        return "#9a6700";
      }
      if (code >= 400) {
        return "#cf222e";
      }
      return "#6e7781";
    }
    String m = label.toUpperCase(Locale.ROOT);
    if ("BYE".equals(m) || "CANCEL".equals(m)) {
      return "#cf222e";
    }
    if ("INVITE".equals(m) || "ACK".equals(m)) {
      return "#1f6feb";
    }
    if ("REGISTER".equals(m)) {
      return "#8250df";
    }
    return "#6f42c1";
  }

  private void showDetail(Row row) {
    if (row == null || row.detail == null || row.detail.isBlank()) {
      detail.clear();
      return;
    }
    detail.setText(row.detail);
  }

  private void setFilter(Kind kind, boolean onlySip) {
    kindFilter = kind;
    sipOnly = onlySip;
    filtered.setPredicate(
        row -> {
          if (sipOnly) {
            return row.kind == Kind.SIP;
          }
          if (kindFilter == null) {
            return true;
          }
          if (kindFilter == Kind.WARN) {
            return row.kind == Kind.WARN || row.kind == Kind.ERR;
          }
          return row.kind == kindFilter;
        });
  }

  private void clear() {
    rows.clear();
    chips.getChildren().setAll(emptyHint);
    detail.clear();
  }

  private static ToggleButton filterBtn(String text, ToggleGroup group, boolean selected) {
    ToggleButton btn = new ToggleButton(text);
    btn.setToggleGroup(group);
    btn.setSelected(selected);
    return btn;
  }

  private static final class RowCell extends ListCell<Row> {
    private final HBox box = new HBox(8);
    private final Label time = new Label();
    private final Label badge = new Label();
    private final Circle dir = new Circle(4);
    private final Label text = new Label();

    RowCell() {
      time.setMinWidth(64);
      time.setStyle("-fx-text-fill: #656d76; -fx-font-family: monospace;");
      badge.setMinWidth(40);
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
        return;
      }
      time.setText(row.time);
      badge.setText(row.kind.label);
      badge.setStyle(
          "-fx-background-color: "
              + row.kind.color
              + "; -fx-text-fill: white; -fx-padding: 1 6 1 6; -fx-background-radius: 8;"
              + " -fx-font-size: 11px;");
      dir.setFill(row.kind == Kind.SIP ? Color.web(row.outgoing ? "#1f6feb" : "#1a7f37") : Color.TRANSPARENT);
      text.setText(row.text);
      if (row.kind == Kind.ERR) {
        text.setStyle("-fx-text-fill: #c0392b;");
      } else if (row.kind == Kind.WARN) {
        text.setStyle("-fx-text-fill: #d35400;");
      } else {
        text.setStyle("-fx-text-fill: #1f2328;");
      }
      setGraphic(box);
    }
  }
}
