package lab.sip.client;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
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
import javafx.util.Duration;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public final class MainApp extends Application {
  private final Map<String, ObservableList<ChatMessage>> sessionMap = new ConcurrentHashMap<>();
  private final Map<String, Integer> unreadCounts = new ConcurrentHashMap<>();
  private final Map<String, Long> lastRoomMessageIds = new ConcurrentHashMap<>();
  private final Map<String, Boolean> privateHistoryLoaded = new ConcurrentHashMap<>();
  private final Map<Long, ChatMessage> privateMessagesById = new ConcurrentHashMap<>();
  private final Set<Long> seenPrivateMessageIds = ConcurrentHashMap.newKeySet();
  private volatile long lastPrivateInboxPollAt;
  private volatile long lastPrivateHistoryPollAt;
  private volatile String privateChatUser = "";
  private final ObservableList<ChatMessage> chatMessages = FXCollections.observableArrayList();
  private final HttpFileClient httpFileClient = new HttpFileClient("http://localhost:8080");

  public ObservableList<ChatMessage> getOrCreateSession(String target) {
    if (target == null || target.isBlank()) {
      target = "default";
    }
    String key = extractUser(target).trim().toLowerCase();
    return sessionMap.computeIfAbsent(key, k -> FXCollections.observableArrayList());
  }

  public void switchChatSession(String target) {
    if (peer != null && target != null && !target.equalsIgnoreCase(peer.getText())) {
      peer.setText(target);
    }
    String key = (target != null && !target.isBlank()) ? extractUser(target).trim().toLowerCase() : "default";
    unreadCounts.put(key, 0);
    if (assembler != null) {
      assembler.updateContactUnread(key, 0);
    }
    ObservableList<ChatMessage> sessionList = getOrCreateSession(key);
    if (chatListView != null) {
      chatListView.setItems(sessionList);
      if (!sessionList.isEmpty()) {
        chatListView.scrollTo(sessionList.size() - 1);
      }
    }
    if (chatPeerLabel != null) {
      if (key.startsWith("room-")) {
        chatPeerLabel.setText("研讨室 · " + target + " (多方共享)");
      } else {
        chatPeerLabel.setText("当前对象: " + target);
      }
    }
    if (!key.startsWith("room-") && !"default".equals(key)) {
      loadPrivateHistory(key);
    }
  }

  public enum OnlineStatus {
    ONLINE,
    OFFLINE,
    UNREACHABLE
  }

  public static OnlineStatus probeSipUserStatus(String sipHost, int sipPort, String targetUser) {
    if (sipHost == null || sipHost.isBlank() || targetUser == null || targetUser.isBlank()) {
      return OnlineStatus.UNREACHABLE;
    }
    String host = sipHost.trim();
    if ("localhost".equalsIgnoreCase(host)) {
      host = "127.0.0.1";
    }
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(1000);
      String branch = "z9hG4bK-prb" + System.currentTimeMillis() + "-" + (int) (Math.random() * 10000);
      String callId = "prb-" + System.currentTimeMillis() + "-" + targetUser + "@" + host;
      String req =
          "OPTIONS sip:" + targetUser + "@" + host + ":" + sipPort + " SIP/2.0\r\n"
              + "Via: SIP/2.0/UDP 127.0.0.1:" + socket.getLocalPort() + ";branch=" + branch + "\r\n"
              + "From: <sip:probe@" + host + ">;tag=p" + System.currentTimeMillis() + "\r\n"
              + "To: <sip:" + targetUser + "@" + host + ">\r\n"
              + "Call-ID: " + callId + "\r\n"
              + "CSeq: 1 OPTIONS\r\n"
              + "Max-Forwards: 70\r\n"
              + "Content-Length: 0\r\n\r\n";
      byte[] bytes = req.getBytes(StandardCharsets.UTF_8);
      InetAddress addr = InetAddress.getByName(host);
      DatagramPacket packet = new DatagramPacket(bytes, bytes.length, addr, sipPort);
      socket.send(packet);

      byte[] buf = new byte[2048];
      DatagramPacket respPacket = new DatagramPacket(buf, buf.length);
      try {
        socket.receive(respPacket);
      } catch (java.net.SocketTimeoutException te) {
        // 轻量快速重发 1 次（符合 RFC 3261 规范，防 UDP 本地微小抖动）
        socket.send(packet);
        socket.receive(respPacket);
      }
      String resp = new String(respPacket.getData(), 0, respPacket.getLength(), StandardCharsets.UTF_8);
      if (resp.startsWith("SIP/2.0 200") || resp.contains(" 200 OK")) {
        return OnlineStatus.ONLINE;
      }
      if (resp.contains(" 404 ") || resp.contains(" 480 ") || resp.contains(" 603 ")) {
        return OnlineStatus.OFFLINE;
      }
      return OnlineStatus.OFFLINE;
    } catch (java.net.SocketTimeoutException e) {
      return OnlineStatus.UNREACHABLE;
    } catch (Exception e) {
      return OnlineStatus.UNREACHABLE;
    }
  }

  public static boolean probeSipUserOnline(String sipHost, int sipPort, String targetUser) {
    return probeSipUserStatus(sipHost, sipPort, targetUser) == OnlineStatus.ONLINE;
  }

  public void handleIncomingInstantMessage(String fromUri, String toUri, String body) {
    String sender = extractUser(fromUri);
    String receiver = extractUser(toUri);
    FileMessage fm = FileMessage.tryParse(body);
    ChatMessage msg =
        (fm != null)
            ? ChatMessage.file(sender, receiver, false, fm, null)
            : ChatMessage.text(sender, receiver, false, body);
    ObservableList<ChatMessage> list = getOrCreateSession(sender);
    list.add(msg);
    String curTarget = (peer != null && peer.getText() != null) ? extractUser(peer.getText().trim()) : "";
    if (sender.equalsIgnoreCase(curTarget)) {
      if (chatListView != null) {
        chatListView.scrollTo(list.size() - 1);
      }
    } else {
      int unread = unreadCounts.getOrDefault(sender.toLowerCase(), 0) + 1;
      unreadCounts.put(sender.toLowerCase(), unread);
      if (assembler != null) {
        assembler.updateContactUnread(sender, unread);
      }
    }
  }

  private String myUsername() {
    return (user != null && user.getText() != null) ? user.getText().trim() : "";
  }

  private static String privateStatusText(String status, boolean outgoing) {
    if (!outgoing) {
      return "";
    }
    if ("DELIVERED".equalsIgnoreCase(status)) {
      return "已送达";
    }
    if ("PENDING".equalsIgnoreCase(status)) {
      return "待送达";
    }
    if ("FAILED".equalsIgnoreCase(status)) {
      return "发送失败";
    }
    return "";
  }

  private void loadPrivateHistory(String target) {
    String key = extractUser(target).toLowerCase();
    String me = myUsername();
    if (key.isBlank() || key.startsWith("room-") || me.isBlank()
        || httpFileClient.getAuthToken() == null || httpFileClient.getAuthToken().isBlank()) {
      return;
    }
    if (privateHistoryLoaded.putIfAbsent(key, Boolean.TRUE) != null) {
      return;
    }
    new Thread(
            () -> {
              try {
                httpFileClient.setBaseUrl(httpServerField.getText().trim());
                List<HttpFileClient.PrivateMessageInfo> list =
                    httpFileClient.fetchPrivateMessages(key, 0);
                Platform.runLater(() -> mergePrivateHistory(key, me, list));
              } catch (Exception ex) {
                privateHistoryLoaded.remove(key);
                Platform.runLater(() -> logs.append("警告", "加载私聊历史失败：" + ex.getMessage()));
              }
            },
            "private-history")
        .start();
  }

  private void mergePrivateHistory(
      String key, String me, List<HttpFileClient.PrivateMessageInfo> list) {
    if (list == null || list.isEmpty()) {
      return;
    }
    ObservableList<ChatMessage> session = getOrCreateSession(key);
    boolean changed = false;
    for (HttpFileClient.PrivateMessageInfo info : list) {
      if (info.id() <= 0) {
        continue;
      }
      ChatMessage known = privateMessagesById.get(info.id());
      boolean outgoing = info.sender() != null && info.sender().equalsIgnoreCase(me);
      if (known != null) {
        known.setStatus(privateStatusText(info.status(), outgoing));
        seenPrivateMessageIds.add(info.id());
        ackIncomingPrivateIfPending(info, outgoing);
        changed = true;
        continue;
      }
      if (seenPrivateMessageIds.contains(info.id())) {
        continue;
      }
      // If the same message is already on screen from SIP, attach the server id
      // instead of adding a duplicate bubble.
      ChatMessage live = findLiveMessage(session, outgoing, info.sender(), info.content());
      if (live != null) {
        live.setBackendId(info.id());
        live.setStatus(privateStatusText(info.status(), outgoing));
        privateMessagesById.put(info.id(), live);
        seenPrivateMessageIds.add(info.id());
        ackIncomingPrivateIfPending(info, outgoing);
        changed = true;
        continue;
      }
      ChatMessage msg =
          ChatMessage.fromServer(
              info.sender(),
              info.receiver(),
              outgoing,
              info.content(),
              info.timestamp(),
              info.id(),
              privateStatusText(info.status(), outgoing));
      session.add(msg);
      privateMessagesById.put(info.id(), msg);
      seenPrivateMessageIds.add(info.id());
      ackIncomingPrivateIfPending(info, outgoing);
      changed = true;
    }
    if (changed) {
      session.sort(Comparator.comparing(ChatMessage::getTimestamp));
      if (chatListView != null && !session.isEmpty()) {
        chatListView.refresh();
        chatListView.scrollTo(session.size() - 1);
      }
    }
  }

  private void ackIncomingPrivateIfPending(
      HttpFileClient.PrivateMessageInfo info, boolean outgoing) {
    if (outgoing || info == null || !"PENDING".equalsIgnoreCase(info.status())) {
      return;
    }
    new Thread(
            () -> {
              try {
                httpFileClient.updatePrivateMessageStatus(info.id(), "DELIVERED");
              } catch (Exception ignored) {
              }
            },
            "private-ack-history")
        .start();
  }

  private static ChatMessage findLiveMessage(
      ObservableList<ChatMessage> session, boolean outgoing, String sender, String content) {
    for (int i = session.size() - 1; i >= 0; i--) {
      ChatMessage m = session.get(i);
      if (m.getBackendId() == null
          && m.isOutgoing() == outgoing
          && content != null
          && content.equals(m.getText())
          && (outgoing || (sender != null && sender.equalsIgnoreCase(m.getSender())))) {
        return m;
      }
    }
    return null;
  }

  /** Pull PENDING messages for this user, show them, then acknowledge delivery. */
  private void pollPrivateInbox() {
    String me = myUsername();
    if (me.isBlank() || httpFileClient.getAuthToken() == null
        || httpFileClient.getAuthToken().isBlank()) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - lastPrivateInboxPollAt < 2000) {
      return;
    }
    lastPrivateInboxPollAt = now;
    new Thread(
            () -> {
              try {
                httpFileClient.setBaseUrl(httpServerField.getText().trim());
                List<HttpFileClient.PrivateMessageInfo> list =
                    httpFileClient.fetchPendingPrivateMessages();
                if (list == null || list.isEmpty()) {
                  return;
                }
                Platform.runLater(
                    () -> {
                      for (HttpFileClient.PrivateMessageInfo info : list) {
                        if (info.id() <= 0 || !seenPrivateMessageIds.add(info.id())) {
                          continue;
                        }
                        // Give the online SIP MESSAGE a few seconds to arrive first.
                        if (info.timestamp() != null
                            && info.timestamp().isAfter(Instant.now().minusSeconds(5))) {
                          seenPrivateMessageIds.remove(info.id());
                          continue;
                        }
                        String sender = info.sender() == null ? "" : info.sender();
                        ObservableList<ChatMessage> session = getOrCreateSession(sender);
                        ChatMessage live = findLiveMessage(session, false, sender, info.content());
                        if (live != null) {
                          live.setBackendId(info.id());
                          privateMessagesById.put(info.id(), live);
                        } else {
                          ChatMessage msg =
                              ChatMessage.fromServer(
                                  sender,
                                  me,
                                  false,
                                  info.content(),
                                  info.timestamp(),
                                  info.id(),
                                  "");
                          session.add(msg);
                          session.sort(Comparator.comparing(ChatMessage::getTimestamp));
                          privateMessagesById.put(info.id(), msg);
                        }
                        String curTarget =
                            (peer != null && peer.getText() != null)
                                ? extractUser(peer.getText().trim())
                                : "";
                        if (live == null) {
                          if (sender.equalsIgnoreCase(curTarget)) {
                            if (chatListView != null) {
                              chatListView.scrollTo(session.size() - 1);
                            }
                          } else {
                            int unread = unreadCounts.getOrDefault(sender.toLowerCase(), 0) + 1;
                            unreadCounts.put(sender.toLowerCase(), unread);
                            if (assembler != null) {
                              assembler.updateContactUnread(sender, unread);
                            }
                          }
                        }
                        new Thread(
                                () -> {
                                  try {
                                    httpFileClient.updatePrivateMessageStatus(
                                        info.id(), "DELIVERED");
                                  } catch (Exception ignored) {
                                  }
                                },
                                "private-ack")
                            .start();
                      }
                    });
              } catch (Exception ignored) {
              }
            },
            "private-inbox")
        .start();
  }

  /** Refresh delivery states for the currently open private conversation. */
  private void refreshActivePrivateStatuses() {
    String target = (peer != null && peer.getText() != null) ? extractUser(peer.getText().trim()) : "";
    if (target.isBlank() || target.startsWith("room-") || myUsername().isBlank()
        || httpFileClient.getAuthToken() == null || httpFileClient.getAuthToken().isBlank()) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - lastPrivateHistoryPollAt < 5000) {
      return;
    }
    lastPrivateHistoryPollAt = now;
    new Thread(
            () -> {
              try {
                httpFileClient.setBaseUrl(httpServerField.getText().trim());
                List<HttpFileClient.PrivateMessageInfo> list =
                    httpFileClient.fetchPrivateMessages(target, 0);
                Platform.runLater(() -> mergePrivateHistory(target, myUsername(), list));
              } catch (Exception ignored) {
              }
            },
            "private-status")
        .start();
  }

  /**
   * Archive an outgoing SIP MESSAGE after its delivery status is known.
   * 2xx = DELIVERED; 404/408/480/486 = PENDING for the offline inbox; other = FAILED.
   */
  private void archiveOutgoingPrivateMessage(String toUri, String body, int code) {
    String receiver = extractUser(toUri);
    if (receiver.isBlank() || receiver.startsWith("room-") || body == null || body.isBlank()) {
      return;
    }
    String status;
    if (code >= 200 && code < 300) {
      status = "DELIVERED";
    } else if (code == 404 || code == 408 || code == 480 || code == 486 || code == 503) {
      status = "PENDING";
    } else {
      status = "FAILED";
    }
    boolean isFile = FileMessage.tryParse(body) != null;
    new Thread(
            () -> {
              try {
                httpFileClient.setBaseUrl(httpServerField.getText().trim());
                HttpFileClient.PrivateMessageInfo info =
                    httpFileClient.savePrivateMessage(receiver, body, isFile, status);
                if (info == null || info.id() <= 0) {
                  return;
                }
                Platform.runLater(
                    () -> {
                      ObservableList<ChatMessage> list = getOrCreateSession(receiver);
                      ChatMessage target = null;
                      for (int i = list.size() - 1; i >= 0; i--) {
                        ChatMessage m = list.get(i);
                        if (m.isOutgoing() && m.getBackendId() == null
                            && body.equals(m.getText())) {
                          target = m;
                          break;
                        }
                      }
                      if (target != null) {
                        target.setBackendId(info.id());
                        target.setStatus(privateStatusText(info.status(), true));
                        privateMessagesById.put(info.id(), target);
                        seenPrivateMessageIds.add(info.id());
                        if (chatListView != null) {
                          chatListView.refresh();
                        }
                      }
                    });
              } catch (Exception ex) {
                Platform.runLater(() -> logs.append("警告", "私聊消息入库失败：" + ex.getMessage()));
              }
            },
            "private-archive")
        .start();
  }

  public static boolean probeRoomMcuReady(String httpBaseUrl) {
    if (httpBaseUrl == null || httpBaseUrl.isBlank()) {
      httpBaseUrl = "http://127.0.0.1:8080";
    }
    try {
      java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
          .connectTimeout(java.time.Duration.ofMillis(800)).build();
      java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
          .uri(java.net.URI.create(httpBaseUrl.replaceAll("/+$", "") + "/api/health"))
          .timeout(java.time.Duration.ofMillis(1200))
          .GET()
          .build();
      java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
      String b = resp.body();
      return resp.statusCode() == 200 && b != null && b.contains("\"connected\":true");
    } catch (Exception e) {
      return false;
    }
  }

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

                    if (isRoomTarget(calleeOrRoom) || isRoomTarget(caller)) {
                      refreshRooms();
                    }

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
              Platform.runLater(() -> handleIncomingInstantMessage(fromUri, toUri, body));
            }

            @Override
            public void instantMessageStatus(
                String toUri, String body, int statusCode, String reason) {
              archiveOutgoingPrivateMessage(toUri, body, statusCode);
              Platform.runLater(
                  () -> {
                    String targetUser = extractUser(toUri);
                    String status =
                        (statusCode == 200) ? "已送达" : ("发送未送达(" + statusCode + " " + reason + ")");
                    boolean matched = false;
                    ObservableList<ChatMessage> list = getOrCreateSession(targetUser);
                    // 1. 优先尝试在发给 targetUser 且待确认的消息中精确匹配正文
                    for (int i = 0; i < list.size(); i++) {
                      ChatMessage m = list.get(i);
                      if (m.isOutgoing() && ("已发送".equals(m.getStatus()) || m.getStatus().isEmpty())) {
                        if (!targetUser.isEmpty() && targetUser.equals(m.getReceiver())) {
                          String expectedBody = m.isFile() ? m.getFile().toWireFormat() : m.getText();
                          if (body != null && !body.isEmpty() && expectedBody.equals(body)) {
                            m.setStatus(status);
                            matched = true;
                            break;
                          }
                        }
                      }
                    }
                    // 2. 匹配不到正文时，只允许落在「发给该用户且仅有唯一一条待确认」的消息上
                    if (!matched && !targetUser.isEmpty()) {
                      int candidateIndex = -1;
                      int candidateCount = 0;
                      for (int i = 0; i < list.size(); i++) {
                        ChatMessage m = list.get(i);
                        if (m.isOutgoing() && ("已发送".equals(m.getStatus()) || m.getStatus().isEmpty())) {
                          if (targetUser.equals(m.getReceiver())) {
                            candidateIndex = i;
                            candidateCount++;
                          }
                        }
                      }
                      if (candidateCount == 1 && candidateIndex >= 0) {
                        list.get(candidateIndex).setStatus(status);
                        matched = true;
                      }
                    }
                    if (!matched) {
                      logs.append("消息", "收到未匹配目标气泡的消息回执: to=" + toUri + ", 状态码=" + statusCode + " (" + reason + ")");
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
  private TextField peer = new TextField("1002");

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
  private boolean roomRefreshFailed = false;

  // CDR and Stats UI elements
  private final ObservableList<HttpFileClient.CdrRecord> cdrRecords = FXCollections.observableArrayList();
  private TableView<HttpFileClient.CdrRecord> cdrTableView;
  private Label totalCallsLabel;
  private Label totalDurationLabel;
  private Label avgDurationLabel;
  private Label peakMembersLabel;

  // Conference Room (MCU) UI elements
  private final ObservableList<HttpFileClient.RoomInfo> roomRecords = FXCollections.observableArrayList();
  private TableView<HttpFileClient.RoomInfo> roomTableView;
  private Label roomCountBadge;
  private HBox inRoomBanner;
  private Label inRoomBannerLabel;
  private Button inRoomExitBtn;
  private Label roomHintLabel;
  /** 最近一次 refreshButtons 探测到的视频能力，避免每个单元格都去枚举一次原生设备。 */
  private volatile boolean videoReadyForCall;
  /** 入会失败/降级的提示要压住 hint 行一段时间，不然下一轮刷新就被覆盖了。 */
  private volatile String roomHintNotice;
  private volatile long roomHintNoticeUntil;

  private TabPane rightTabPane;
  private Tab chatTab;
  private Tab roomTab;
  private Button loginBtn;
  private CheckBox cameraToggleBox;
  private MainViewAssembler assembler;

  private String campusIp = "";

  public CheckBox getCameraToggleBox() {
    if (cameraToggleBox == null) {
      boolean cameraOn = sip.isCameraEnabled();
      cameraToggleBox = new CheckBox(cameraOn ? "摄像头: 已开启" : "摄像头: 已禁用");
      cameraToggleBox.setSelected(cameraOn);
      cameraToggleBox.getStyleClass().setAll("camera-toggle-btn", cameraOn ? "camera-toggle-on" : "camera-toggle-off");
      cameraToggleBox.setOnAction(e -> {
        boolean enabled = cameraToggleBox.isSelected();
        sip.setCameraEnabled(enabled);
        cameraToggleBox.setText(enabled ? "摄像头: 已开启" : "摄像头: 已禁用");
        cameraToggleBox.getStyleClass().setAll("camera-toggle-btn", enabled ? "camera-toggle-on" : "camera-toggle-off");
        if (!enabled && sip.isPreviewActive()) {
          sip.stopPreview();
        }
        if (sip.isCallActive()) {
          sip.setCallVideoTransmit(enabled);
          String remote = sip.getActiveCallRemoteUri();
          if (remote != null && (remote.contains("room-") || remote.contains("room"))) {
            String mySip = user.getText().trim();
            httpFileClient.toggleRoomVmute(remote, mySip, !enabled);
            if (logs != null) {
              logs.append("通话", enabled
                  ? "本地摄像头已开启，MCU 会议室画面已恢复实时视频"
                  : "本地摄像头已关闭，MCU 会议室画面已切换为您的个人头像");
            }
          }
        }
        refreshButtons();
        if (logs != null && !sip.isCallActive()) {
          logs.append("系统", "本地摄像头已" + (enabled ? "开启" : "禁用") + " (呼叫与入会将自动" + (enabled ? "支持视频" : "以纯语音模式进行") + ")");
        }
      });
    }
    return cameraToggleBox;
  }

  public Button getPreviewBtn() {
    if (previewBtn == null) {
      previewBtn = new Button("摄像头预览");
    }
    return previewBtn;
  }


  @Override
  public void start(Stage stage) {
    this.primaryStage = stage;
    Map<String, String> named = (getParameters() != null) ? getParameters().getNamed() : Map.of();
    LocalPrefs.load();
    campusIp = CampusLan.detect();

    server = new TextField();
    if (!LocalPrefs.server.isEmpty()) {
      server.setText(LocalPrefs.server);
    } else if (CampusLan.probeTcp("127.0.0.1")) {
      server.setText("127.0.0.1");
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
    httpServerField.textProperty().addListener((obs, oldV, newV) -> {
      httpFileClient.setBaseUrl(newV != null ? newV.trim() : "");
    });
    server.textProperty().addListener((obs, oldV, newV) -> {
      if (newV != null) {
        String currentHttp = httpServerField.getText().trim();
        boolean isDefaultHttp = currentHttp.isEmpty()
            || "http://localhost:8080".equalsIgnoreCase(currentHttp)
            || "http://127.0.0.1:8080".equalsIgnoreCase(currentHttp)
            || (oldV != null && currentHttp.equals("http://" + oldV.trim() + ":8080"));
        String s = newV.trim();
        if (isDefaultHttp && !s.isEmpty() && !s.equals("127.0.0.1") && !s.equalsIgnoreCase("localhost")) {
          httpServerField.setText("http://" + s + ":8080");
        }
      }
    });

    domain = new TextField(named.getOrDefault("domain", "lab"));
    user = new TextField(named.getOrDefault("user", "1001"));
    password = new PasswordField();
    password.setText(named.getOrDefault("password", user.getText().trim()));
    user.textProperty().addListener((obs, oldV, newV) -> {
      if (password.getText().equals(oldV)) {
        password.setText(newV);
      }
    });
    sipPort = new TextField(named.getOrDefault("port", "5062"));
    peer = new TextField(named.getOrDefault("peer", "1002"));
    transport = new ComboBox<>();
    transport.getItems().addAll("tcp", "udp");
    transport.setValue("tcp");
    if ("udp".equalsIgnoreCase(named.getOrDefault("transport", ""))) {
      transport.setValue("udp");
    }
    if (named.containsKey("server")) {
      server.setText(named.get("server"));
    } else if (named.containsKey("host")) {
      server.setText(named.get("host"));
    }
    if (named.containsKey("http")) {
      httpServerField.setText(named.get("http"));
      httpFileClient.setBaseUrl(named.get("http"));
    } else if (named.containsKey("http-port") || named.containsKey("http_port")) {
      String portStr = named.containsKey("http-port") ? named.get("http-port") : named.get("http_port");
      String host = "127.0.0.1";
      if (named.containsKey("server")) {
        host = named.get("server");
      } else if (named.containsKey("host")) {
        host = named.get("host");
      }
      String derived = "http://" + host + ":" + portStr;
      httpServerField.setText(derived);
      httpFileClient.setBaseUrl(derived);
    } else if (named.containsKey("server") || named.containsKey("host")) {
      String s = server.getText().trim();
      if (!s.isEmpty() && !s.equals("127.0.0.1") && !s.equalsIgnoreCase("localhost")) {
        String derivedHttp = "http://" + s + ":8080";
        httpServerField.setText(derivedHttp);
        httpFileClient.setBaseUrl(derivedHttp);
      }
    }

    Button fillLan = new Button("填入本机 IP");
    startEngine = new Button("启动引擎");
    register = new Button("注册/上线");
    callAudio = new Button("语音呼叫");
    callVideo = new Button("视频呼叫");
    callVideo.setStyle("-fx-background-color: #1f6feb; -fx-text-fill: white; -fx-font-weight: bold;");
    previewBtn = getPreviewBtn();
    answerVideo = new Button("接听(视频)");
    answerVideo.getStyleClass().setAll("btn-primary");
    answerAudio = new Button("接听(语音)");
    answerAudio.getStyleClass().setAll("btn-primary");
    hangup = new Button("挂断");
    hangup.getStyleClass().setAll("btn-danger");

    Button testHttpBtn = new Button("测试后台");
    httpStatusBadge = new Label("后台: 未检测");
    httpStatusBadge.getStyleClass().setAll("badge-warn");
    httpStatusBadge.setMaxWidth(Double.MAX_VALUE);
    httpStatusBadge.setAlignment(Pos.CENTER);
    httpStatusBadge.setTooltip(new Tooltip("后台 HTTP 服务尚未检测"));

    logs = new LogView();
    callStatus = new Label("SIP: 未启动");
    callStatus.getStyleClass().setAll("badge-offline");
    callStatus.setMaxWidth(Double.MAX_VALUE);
    callStatus.setAlignment(Pos.CENTER);
    callStatus.setTooltip(new Tooltip("SIP 通信引擎尚未启动"));
    HBox.setHgrow(callStatus, Priority.ALWAYS);
    HBox.setHgrow(httpStatusBadge, Priority.ALWAYS);

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
          logs.append("文件", "正在测试后台 HTTP 服务: " + url + "...");
          new Thread(
                  () -> {
                    String activeUrl = HttpFileClient.discoverBackendUrl(url);
                    if (activeUrl != null && !activeUrl.equalsIgnoreCase(url)) {
                      final String switched = activeUrl;
                      Platform.runLater(
                          () -> {
                            httpServerField.setText(switched);
                            LocalPrefs.save(server.getText(), "tcp", switched);
                            logs.append("系统", "原后台端口不可达或被占用，已自动发现并切换至: " + switched);
                          });
                      httpFileClient.setBaseUrl(switched);
                    } else {
                      httpFileClient.setBaseUrl(url);
                    }
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
                    final String finalTarget = httpFileClient.getBaseUrl();
                    Platform.runLater(
                        () -> {
                          if (healthy) {
                            httpStatusBadge.setText("后台: 正常");
                            httpStatusBadge.getStyleClass().setAll("badge-online");
                            httpStatusBadge.setTooltip(new Tooltip("后台服务正常: " + finalTarget + authMsg));
                            logs.append("文件", "后台服务正常 " + finalTarget + authMsg);
                          } else {
                            httpStatusBadge.setText("后台: 不可达");
                            httpStatusBadge.getStyleClass().setAll("badge-offline");
                            httpStatusBadge.setTooltip(new Tooltip("后台 HTTP 连接失败: " + finalTarget));
                            logs.append("警告", "后台 HTTP 连接失败: " + finalTarget + "，请确认 Spring Boot 已启动");
                          }
                          LocalPrefs.save(server.getText(), "tcp", finalTarget);
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

    ContextMenu previewMenu = new ContextMenu();
    MenuItem mirrorToggleItem = new MenuItem();
    previewMenu.setOnShowing(
        e ->
            mirrorToggleItem.setText(
                sip.isPreviewMirrorEnabled() ? "切换为真实模式 (非镜像)" : "切换为镜像模式 (照镜子直觉)"));
    mirrorToggleItem.setOnAction(e -> run(sip::togglePreviewMirror));
    previewMenu.getItems().add(mirrorToggleItem);
    previewBtn.setContextMenu(previewMenu);
    previewBtn.setTooltip(new Tooltip("点击开启/关闭摄像头预览；右键可切换镜像/真实模式"));

    cameraToggleBox = getCameraToggleBox();

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

    Button chip1001 = new Button("1001 (测试账号 1)");
    chip1001.setOnAction(e -> peer.setText("1001"));
    Button chip1002 = new Button("1002 (测试账号 2)");
    chip1002.setOnAction(e -> peer.setText("1002"));
    Button chip1003 = new Button("1003 (测试账号 3)");
    chip1003.setOnAction(e -> peer.setText("1003"));
    Button chipRoom = new Button("room-101 (MCU 研讨室)");
    chipRoom.setOnAction(e -> peer.setText("room-101"));

    // 来电提示横幅
    incomingNotice = new Label("收到来电");
    incomingNotice.setStyle("-fx-font-weight: bold; -fx-text-fill: #1a7f37;");
    incomingBox = new HBox(10, incomingNotice, answerVideo, answerAudio);
    incomingBox.setAlignment(Pos.CENTER_LEFT);
    incomingBox.setPadding(new Insets(6, 12, 6, 12));
    incomingBox.setStyle(
        "-fx-background-color: #dafbe1; -fx-background-radius: 6; -fx-border-color: #4ac26b; -fx-border-radius: 6;");
    incomingBox.setVisible(false);
    incomingBox.setManaged(false);

    // 视频功能控制与状态行
    videoBadge = new Label("未检测");
    videoBadge.getStyleClass().setAll("badge-offline");
    videoDetailLabel = new Label("启动引擎后将自动探测 H.264 与采集设备");

    loginBtn = new Button("登录后台");
    loginBtn.getStyleClass().setAll("btn-primary");
    loginBtn.setOnAction(e -> performLogin(false));

    // ======== 右侧面板：消息与文件传输 (SIP MESSAGE + HTTP 文件) ========
    chatPeerLabel = new Label("当前对象: " + peer.getText().trim());
    chatPeerLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #57606a;");
    peer.textProperty().addListener((obs, oldV, newV) -> switchChatSession(newV));

    Label chatHeaderTitle = new Label("消息与文件传输");
    chatHeaderTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #1E293B;");
    Button clearChatBtn = new Button("清空");
    clearChatBtn.getStyleClass().setAll("btn-secondary");
    clearChatBtn.setOnAction(e -> getOrCreateSession(peer.getText().trim()).clear());

    Region chatSpacer = new Region();
    HBox.setHgrow(chatSpacer, Priority.ALWAYS);
    HBox chatHeader = new HBox(8, chatHeaderTitle, chatPeerLabel, chatSpacer, clearChatBtn);
    chatHeader.setAlignment(Pos.CENTER_LEFT);
    chatHeader.setPadding(new Insets(4, 4, 4, 4));

    chatListView = new ListView<>(getOrCreateSession(peer.getText().trim()));
    chatListView.setCellFactory(lv -> new ChatMessageCell());
    chatListView.getStyleClass().add("chat-list-view");
    VBox.setVgrow(chatListView, Priority.ALWAYS);

    chatInput = new TextField();
    chatInput.setPromptText("输入文字消息，按 Enter 发送...");
    HBox.setHgrow(chatInput, Priority.ALWAYS);
    chatInput.setOnAction(e -> sendTextMessage());

    sendMsgBtn = new Button("发送");
    sendMsgBtn.getStyleClass().setAll("btn-primary");
    sendMsgBtn.setOnAction(e -> sendTextMessage());

    sendFileBtn = new Button("发送文件...");
    sendFileBtn.getStyleClass().setAll("btn-secondary");
    sendFileBtn.setOnAction(e -> sendFile(primaryStage));

    fileProgressLabel = new Label();
    fileProgressLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #2481CC;");
    fileProgressLabel.setVisible(false);
    fileProgressLabel.setManaged(false);

    HBox inputActions = new HBox(6, chatInput, sendMsgBtn, sendFileBtn);
    inputActions.setAlignment(Pos.CENTER_LEFT);

    VBox chatInputBox = new VBox(6, fileProgressLabel, inputActions);
    chatInputBox.setPadding(new Insets(6, 0, 0, 0));

    VBox rightPane = new VBox(8, chatHeader, chatListView, chatInputBox);
    rightPane.setPadding(new Insets(10));
    rightPane.setMinWidth(360);
    rightPane.getStyleClass().add("chat-container");

    rightTabPane = new TabPane();
    rightTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    chatTab = new Tab("消息与文件", rightPane);
    chatTab.setClosable(false);

    VBox roomPane = buildRoomPane();
    roomTab = new Tab("会议房间 (MCU)", roomPane);
    roomTab.setClosable(false);

    Timeline roomPollingTimeline =
        new Timeline(new KeyFrame(Duration.seconds(4), e -> refreshRooms()));
    roomPollingTimeline.setCycleCount(Animation.INDEFINITE);
    roomTab.selectedProperty().addListener((obs, oldVal, newVal) -> {
      if (Boolean.TRUE.equals(newVal)) {
        refreshRooms();
        roomPollingTimeline.play();
      } else {
        roomPollingTimeline.stop();
      }
    });

    VBox cdrPane = buildCdrPane();
    Tab cdrTab = new Tab("通话记录与统计", cdrPane);
    cdrTab.setClosable(false);

    StackPane consoleContainer = new StackPane(logs);
    logs.setupDockHost(consoleContainer, stage);
    Tab consoleTab = new Tab("信令与控制台", consoleContainer);
    consoleTab.setClosable(false);

    rightTabPane.getTabs().addAll(chatTab, roomTab, cdrTab, consoleTab);

    this.assembler = new MainViewAssembler();
    assembler.setOnAvatarClicked(this::showAvatarSettingsDialog);
    BorderPane root =
        assembler.assembleProductUi(
            stage,
            callStatus,
            videoDetailLabel,
            httpStatusBadge,
            peer,
            new Button[] {chip1001, chip1002, chip1003, chipRoom},
            callVideo,
            callAudio,
            hangup,
            previewBtn,
            cameraToggleBox,
            incomingBox,
            server,
            fillLan,
            httpServerField,
            testHttpBtn,
            transport,
            domain,
            user,
            password,
            sipPort,
            startEngine,
            register,
            loginBtn,
            logs,
            rightTabPane);

    Scene scene = new Scene(root, 1080, 750);
    try {
      scene.getStylesheets().add(getClass().getResource("/modern-theme.css").toExternalForm());
    } catch (Exception ignored) {
    }
    stage.setTitle("SIP 客户端 · 阶段 5 房间 MCU 与音视频会议");
    stage.setScene(scene);

    Timeline probeAndPollTimeline =
        new Timeline(new KeyFrame(Duration.millis(2500), e -> pollOnlineStatusesAndRoomMessages()));
    probeAndPollTimeline.setCycleCount(Animation.INDEFINITE);

    stage.setOnCloseRequest(
        e -> {
          probeAndPollTimeline.stop();
          roomPollingTimeline.stop();
          if (logs != null) {
            logs.dockBack();
          }
          if (httpFileClient != null) {
            new Thread(() -> {
              try {
                httpFileClient.logout();
              } catch (Throwable ignored) {
              }
            }).start();
          }
          sip.shutdown();
        });
    stage.show();
    switchChatSession(peer.getText().trim());
    probeAndPollTimeline.play();
    pollOnlineStatusesAndRoomMessages();

    String snapProp = System.getProperty("ui.snapshot");
    final String snapshotPath = (snapProp != null && !snapProp.isBlank()) ? snapProp : named.get("snapshot");
    if (snapshotPath != null && !snapshotPath.isBlank()) {
      new Thread(() -> {
        try {
          Thread.sleep(800);
          Platform.runLater(() -> {
            if (!startEngine.isDisabled()) startEngine.fire();
          });
          Thread.sleep(600);
          Platform.runLater(() -> {
            if (!register.isDisabled()) register.fire();
          });
          Thread.sleep(1800);
          Platform.runLater(() -> {
            try {
              WritableImage image = scene.snapshot(null);
              int w = (int) image.getWidth();
              int h = (int) image.getHeight();
              BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
              PixelReader pr = image.getPixelReader();
              for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                  bi.setRGB(x, y, pr.getArgb(x, y));
                }
              }
              File out = new File(snapshotPath);
              if (out.getParentFile() != null) {
                out.getParentFile().mkdirs();
              }
              ImageIO.write(bi, "png", out);
              System.out.println("SNAPSHOT_SAVED_SUCCESSFULLY: " + out.getAbsolutePath());
              sip.shutdown();
              Platform.exit();
            } catch (Exception ex) {
              ex.printStackTrace();
              Platform.exit();
            }
          });
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      }, "snapshot-thread").start();
    }

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
    logs.appendApp("阶段 5 房间 MCU：支持进入研讨室与 FreeSWITCH 3×3 混流画面，ESL 实时人数同步。");
    logs.appendApp("同机第二窗口：mvn javafx:run -Djavafx.args=\"--user=1002 --port=5062 --peer=1001\"");
    refreshButtons();
    performLogin(true);
    refreshRooms();
  }

  private void pollOnlineStatusesAndRoomMessages() {
    new Thread(
        () -> {
          // 1. 探活联系人真实在线状态 (1001, 1002, 1003)
          String sipHost = (server != null && server.getText() != null) ? server.getText().trim() : "127.0.0.1";
          int sipPortNum = 5060;
          String myUser = (user != null && user.getText() != null) ? user.getText().trim() : "";
          java.util.Set<String> allTargets = (assembler != null && !assembler.getContactTargetIds().isEmpty())
              ? assembler.getContactTargetIds()
              : java.util.Set.of("1001", "1002", "1003", "room-101");

          for (String target : allTargets) {
            if (target.startsWith("room")) {
              continue;
            }
            boolean online;
            if (!myUser.isEmpty() && target.equalsIgnoreCase(myUser) && sip.isRegistered()) {
              online = true;
            } else {
              online = probeSipUserOnline(sipHost, sipPortNum, target);
            }
            if (assembler != null) {
              assembler.updateContactStatus(target, online);
            }
          }

          boolean mcuReady = probeRoomMcuReady(httpServerField != null ? httpServerField.getText().trim() : "http://127.0.0.1:8080");
          if (assembler != null) {
            for (String target : allTargets) {
              if (target.startsWith("room")) {
                assembler.updateContactStatus(target, mcuReady);
              }
            }
          }

          // 2. 轮询当前激活研讨室的增量群聊消息
          String currentPeer = (peer != null && peer.getText() != null) ? peer.getText().trim() : "";
          if (isRoomTarget(currentPeer)) {
            String roomUri = extractUser(currentPeer);
            long lastId = lastRoomMessageIds.getOrDefault(roomUri.toLowerCase(), 0L);
            try {
              httpFileClient.setBaseUrl(httpServerField.getText().trim());
              List<HttpFileClient.RoomMessageInfo> list = httpFileClient.fetchRoomMessages(roomUri, lastId);
              if (list != null && !list.isEmpty()) {
                long maxId = lastId;
                List<ChatMessage> toAdd = new ArrayList<>();
                for (HttpFileClient.RoomMessageInfo m : list) {
                  if (m.id() > maxId) {
                    maxId = m.id();
                  }
                  if (!m.sender().equalsIgnoreCase(myUser)) {
                    FileMessage fm = m.isFile() ? FileMessage.tryParse(m.content()) : null;
                    ChatMessage cm = (fm != null)
                        ? ChatMessage.file(m.sender(), roomUri, false, fm, null)
                        : ChatMessage.text(m.sender(), roomUri, false, m.content());
                    toAdd.add(cm);
                  }
                }
                lastRoomMessageIds.put(roomUri.toLowerCase(), maxId);
                if (!toAdd.isEmpty()) {
                  Platform.runLater(
                      () -> {
                        ObservableList<ChatMessage> session = getOrCreateSession(roomUri);
                        session.addAll(toAdd);
                        String activeTarget = (peer != null && peer.getText() != null) ? extractUser(peer.getText().trim()) : "";
                        if (roomUri.equalsIgnoreCase(activeTarget) && chatListView != null) {
                          chatListView.scrollTo(session.size() - 1);
                        }
                      });
                }
              }
            } catch (Exception ignored) {
            }
          }
        },
        "probe-and-room-poll")
        .start();
    pollPrivateInbox();
    refreshActivePrivateStatuses();
  }

  private void sendTextMessage() {
    String text = chatInput.getText().trim();
    if (text.isEmpty()) {
      return;
    }
    String targetPeer = peer.getText().trim();
    String myUser = user.getText().trim();
    if (isRoomTarget(targetPeer)) {
      chatInput.clear();
      new Thread(
          () -> {
            try {
              httpFileClient.setBaseUrl(httpServerField.getText().trim());
              httpFileClient.sendRoomMessage(targetPeer, myUser, text, false);
              Platform.runLater(
                  () -> {
                    ChatMessage msg = ChatMessage.text(myUser, targetPeer, true, text);
                    ObservableList<ChatMessage> list = getOrCreateSession(targetPeer);
                    list.add(msg);
                    if (chatListView != null) {
                      chatListView.scrollTo(list.size() - 1);
                    }
                  });
            } catch (Exception ex) {
              Platform.runLater(() -> logs.append("错误", "发送房间消息失败: " + ex.getMessage()));
            }
          },
          "room-text-send")
          .start();
      return;
    }
    String targetDomain = domain.getText().trim();
    run(
        () -> {
          sip.sendMessage(targetPeer, targetDomain, text);
          ChatMessage msg = ChatMessage.text(myUser, targetPeer, true, text);
          ObservableList<ChatMessage> list = getOrCreateSession(targetPeer);
          list.add(msg);
          chatInput.clear();
          if (chatListView != null) {
            chatListView.scrollTo(list.size() - 1);
          }
        });
  }

  private void sendFile(Stage owner) {
    String targetPeer = peer.getText().trim();
    boolean isRoom = isRoomTarget(targetPeer);
    if (!isRoom && !sip.isRegistered()) {
      logs.append("警告", "尚未注册成功，无法发送点对点文件消息");
      return;
    }
    FileChooser chooser = new FileChooser();
    chooser.setTitle("选择要发送的文件");
    File file = chooser.showOpenDialog(owner);
    if (file == null) {
      return;
    }

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
                      if (isRoom) {
                        new Thread(
                            () -> {
                              try {
                                httpFileClient.sendRoomMessage(targetPeer, myUser, fm.toWireFormat(), true);
                                Platform.runLater(
                                    () -> {
                                      ChatMessage msg = ChatMessage.file(myUser, targetPeer, true, fm, file.toPath());
                                      ObservableList<ChatMessage> list = getOrCreateSession(targetPeer);
                                      list.add(msg);
                                      if (chatListView != null) {
                                        chatListView.scrollTo(list.size() - 1);
                                      }
                                      logs.append("文件", "研讨室文件共享成功: " + fm.getName() + " (" + FileMessage.formatSize(fm.getSize()) + ")");
                                    });
                              } catch (Exception ex) {
                                Platform.runLater(() -> logs.append("错误", "房间文件广播失败: " + ex.getMessage()));
                              }
                            },
                            "room-file-broadcast")
                            .start();
                      } else {
                        fileProgressLabel.setText("上传完成，正在发送 SIP MESSAGE 通知...");
                        run(
                            () -> {
                              sip.sendMessage(targetPeer, targetDomain, fm.toWireFormat());
                              ChatMessage msg = ChatMessage.file(myUser, targetPeer, true, fm, file.toPath());
                              ObservableList<ChatMessage> list = getOrCreateSession(targetPeer);
                              list.add(msg);
                              if (chatListView != null) {
                                chatListView.scrollTo(list.size() - 1);
                              }
                              logs.append("文件", "文件通知已发送: " + fm.getName() + " (" + FileMessage.formatSize(fm.getSize()) + ")");
                            });
                      }
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
                      sendFileBtn.setDisable(false);
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
    boolean incomingVideo = incoming && sip.isIncomingVideo();
    SipService.VideoDetails vd = sip.queryVideoDetails();
    boolean videoSupported = vd.supported;
    videoReadyForCall = videoSupported;
    boolean hasCapture = vd.captureDevId >= 0;

    boolean cameraOn = sip.isCameraEnabled();
    if (cameraToggleBox != null) {
      if (cameraToggleBox.isSelected() != cameraOn) {
        cameraToggleBox.setSelected(cameraOn);
      }
      cameraToggleBox.setText(cameraOn ? "摄像头: 已开启" : "摄像头: 已禁用");
      cameraToggleBox.getStyleClass().setAll("camera-toggle-btn", cameraOn ? "camera-toggle-on" : "camera-toggle-off");
    }
    if (previewBtn != null) {
      previewBtn.setDisable(!on || !videoSupported || !hasCapture || !cameraOn || sip.isInCall());
      if (sip.isInCall()) {
        previewBtn.setText("通话中请用摄像头开关");
      } else if (!cameraOn) {
        previewBtn.setText("摄像头已禁用");
      } else if (!hasCapture && videoSupported) {
        previewBtn.setText("无摄像头");
      } else {
        previewBtn.setText(sip.isPreviewActive() ? "关闭预览" : "摄像头预览");
      }
    }

    if (startEngine == null) {
      return;
    }

    startEngine.setDisable(on);
    register.setDisable(!on);
    if (loginBtn != null) {
      loginBtn.setDisable(!on);
    }

    callAudio.setDisable(!reg);
    callVideo.setDisable(!reg || !videoSupported);

    boolean canAnswerVideo = incoming && incomingVideo && videoSupported;
    answerAudio.setDisable(!incoming);
    answerAudio.setText(incomingVideo ? "仅接听语音" : "接听(语音)");
    answerVideo.setDisable(!canAnswerVideo);
    answerVideo.setVisible(canAnswerVideo);
    answerVideo.setManaged(canAnswerVideo);
    incomingBox.setVisible(incoming);
    incomingBox.setManaged(incoming);
    if (incoming) {
      if (incomingVideo && videoSupported) {
        incomingNotice.setText("收到视频来电：可接听视频，或仅接听语音");
      } else if (incomingVideo) {
        incomingNotice.setText("收到视频来电：本机未启用视频，请选择【接听(语音)】");
      } else {
        incomingNotice.setText("收到语音来电：请选择【接听(语音)】");
      }
    }

    hangup.setDisable(!on && !sip.isInCall());
    boolean inRoom = sip.isInRoomCall();
    if (inRoom) {
      String target = sip.currentCallTarget();
      String userPart = extractUser(target);
      String roomName = userPart;
      for (HttpFileClient.RoomInfo r : roomRecords) {
        if (extractUser(r.sipUri()).equalsIgnoreCase(userPart)) {
          roomName = r.name();
          break;
        }
      }
      boolean isVideo = sip.isCurrentCallVideo();
      String modeDesc = isVideo ? "视频参会 · 3×3 混流" : "语音参会 · 纯音频";
      if (inRoomBanner != null && inRoomBannerLabel != null) {
        inRoomBannerLabel.setText("当前正在研讨室【" + roomName + "】中 (" + target + " · " + modeDesc + ")");
        inRoomBanner.setVisible(true);
        inRoomBanner.setManaged(true);
      }
      hangup.setText("退出会议");
    } else {
      if (inRoomBanner != null) {
        inRoomBanner.setVisible(false);
        inRoomBanner.setManaged(false);
      }
      hangup.setText("挂断");
    }
    if (roomTableView != null) {
      roomTableView.refresh();
    }
    if (roomHintLabel != null) {
      long now = System.currentTimeMillis();
      if (roomHintNotice != null && now < roomHintNoticeUntil) {
        roomHintLabel.setText(roomHintNotice);
        roomHintLabel.setStyle(
            "-fx-font-size: 11px; -fx-text-fill: #B42318; -fx-font-weight: bold; -fx-padding: 2 0 2 0;");
      } else {
        roomHintNotice = null;
        roomHintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B; -fx-padding: 2 0 2 0;");
        if (!on) {
          roomHintLabel.setText("提示：引擎还没启动，点【启动引擎】并注册成功后才能加入会议。");
        } else if (!reg) {
          roomHintLabel.setText("提示：SIP 尚未注册成功，注册完成后才能加入会议。");
        } else {
          roomHintLabel.setText(
              "提示：点【加入会议】按当前设备自动选音视频；点【视频】强制拉 3×3 混流画布；点【语音】纯音频入会。");
        }
      }
    }

    String fullSip = sip.statusText();
    String shortSip;
    if (!on) {
      shortSip = "SIP: 未启动";
    } else if (sip.isInCall()) {
      shortSip = sip.isInRoomCall() ? "SIP: 在会中" : "SIP: 通话中";
    } else if (reg) {
      shortSip = "SIP: 在线";
    } else if (fullSip.contains("失败")) {
      shortSip = "SIP: 离线";
    } else {
      shortSip = "SIP: 未注册";
    }
    callStatus.setText(shortSip);
    callStatus.setTooltip(new Tooltip(fullSip));
    if (reg) {
      callStatus.getStyleClass().setAll("badge-online");
    } else if (on) {
      callStatus.getStyleClass().setAll("badge-warn");
    } else {
      callStatus.getStyleClass().setAll("badge-offline");
    }

    if (assembler != null) {
      assembler.updateOnlineButton(on, reg);
    }

    if (sendMsgBtn != null) {
      sendMsgBtn.setDisable(!reg);
    }
    if (sendFileBtn != null && !fileProgressLabel.isVisible()) {
      sendFileBtn.setDisable(!reg);
    }

    if (!on) {
      callStatus.getStyleClass().setAll("badge-offline");
      videoBadge.setText("引擎未启动");
      videoBadge.getStyleClass().setAll("badge-offline");
      videoDetailLabel.setText("启动引擎后将自动探测 H.264 编解码器与视频输入输出设备。");
    } else if (reg) {
      callStatus.getStyleClass().setAll("badge-online");
      if (videoSupported) {
        videoBadge.setText("视频就绪 (" + vd.codec + ")");
        videoBadge.getStyleClass().setAll("badge-online");
        String capDesc = hasCapture ? vd.captureDev : "无可用摄像头（仅可接收画面）";
        videoDetailLabel.setText("采集: " + capDesc + " | 渲染: " + vd.renderDev + " | 接通后拉起独立原生低延迟窗口");
      } else {
        videoBadge.setText("阶段 1 纯音频模式");
        videoBadge.getStyleClass().setAll("badge-warn");
        videoDetailLabel.setText("底层未启用 H.264；呼叫与接听已自动降级为语音通话。");
      }
    } else {
      callStatus.getStyleClass().setAll("badge-warn");
      if (videoSupported) {
        videoBadge.setText("视频就绪 (" + vd.codec + ")");
        videoBadge.getStyleClass().setAll("badge-online");
        String capDesc = hasCapture ? vd.captureDev : "无可用摄像头（仅可接收画面）";
        videoDetailLabel.setText("采集: " + capDesc + " | 渲染: " + vd.renderDev + " | 引擎已启动，等待 SIP 注册上线");
      } else {
        videoBadge.setText("阶段 1 纯音频模式");
        videoBadge.getStyleClass().setAll("badge-warn");
        videoDetailLabel.setText("底层未启用 H.264；引擎已启动，等待 SIP 注册上线。");
      }
    }
  }

  private void performLogin(boolean silent) {
    String url = httpServerField.getText().trim();
    String u = user.getText().trim();
    String p = password.getText();
    if (!silent) {
      logs.append("系统", "正在登录后台 HTTP 服务: " + url + " (用户 " + u + ")...");
    }
    new Thread(
            () -> {
              String activeUrl = HttpFileClient.discoverBackendUrl(url);
              if (activeUrl != null && !activeUrl.equalsIgnoreCase(url)) {
                final String switched = activeUrl;
                Platform.runLater(
                    () -> {
                      httpServerField.setText(switched);
                      LocalPrefs.save(server.getText(), "tcp", switched);
                      logs.append("系统", "原后台端口不可达或被占用，已自动发现并切换至: " + switched);
                    });
                httpFileClient.setBaseUrl(switched);
              } else {
                httpFileClient.setBaseUrl(url);
              }
              try {
                String token = httpFileClient.login(u, p);
                Platform.runLater(
                    () -> {
                      httpStatusBadge.setText("后台: 已登录");
                      httpStatusBadge.getStyleClass().setAll("badge-online");
                      String tokenSample = (token != null && token.length() > 8) ? token.substring(0, 8) + "..." : token;
                      httpStatusBadge.setTooltip(new Tooltip("后台已登录: " + u + " (Token: " + tokenSample + ")"));
                      logs.append("系统", "后台登录成功: " + u + " (Token: " + tokenSample + ")");
                      if (assembler != null) {
                        String av = httpFileClient.getUserAvatarUrl();
                        String fullAv = (av != null && !av.isBlank()) ? httpFileClient.getBaseUrl() + av : null;
                        assembler.updateNavAvatar(fullAv, u);
                      }
                      refreshContactAvatars();
                      refreshCdrAndStats();
                      refreshRooms();
                      String chatUser = user.getText().trim();
                      if (!chatUser.equalsIgnoreCase(privateChatUser)) {
                        privateChatUser = chatUser;
                        privateHistoryLoaded.clear();
                        seenPrivateMessageIds.clear();
                        privateMessagesById.clear();
                      }
                      privateHistoryLoaded.remove(extractUser(peer.getText()).toLowerCase());
                      loadPrivateHistory(peer.getText().trim());
                      pollPrivateInbox();
                    });
              } catch (Exception ex) {
                Platform.runLater(
                    () -> {
                      if (!silent) {
                        httpStatusBadge.setText("后台: 失败");
                        httpStatusBadge.getStyleClass().setAll("badge-offline");
                        httpStatusBadge.setTooltip(new Tooltip("后台登录失败: " + ex.getMessage()));
                        logs.append("警告", "后台登录失败: " + ex.getMessage());
                      } else {
                        httpStatusBadge.setText("后台: 未登录");
                        httpStatusBadge.getStyleClass().setAll("badge-warn");
                        httpStatusBadge.setTooltip(new Tooltip("后台尚未登录，可点击【网络与账号设置】进行登录"));
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
    totalCallsLabel = new Label("通话总数: 0 次");
    totalDurationLabel = new Label("总通话时长: 0 秒");
    avgDurationLabel = new Label("平均时长: 0.0 秒");
    peakMembersLabel = new Label("房间峰值: 0 人");

    HBox statsCard = new HBox(12);
    statsCard.setAlignment(Pos.CENTER_LEFT);
    for (Label statLabel :
        new Label[] {totalCallsLabel, totalDurationLabel, avgDurationLabel, peakMembersLabel}) {
      statLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1E293B;");
      HBox card = new HBox(statLabel);
      card.getStyleClass().add("metric-card");
      card.setAlignment(Pos.CENTER);
      HBox.setHgrow(card, Priority.ALWAYS);
      statsCard.getChildren().add(card);
    }

    Button refreshCdrBtn = new Button("刷新记录");
    refreshCdrBtn.getStyleClass().setAll("btn-secondary");
    refreshCdrBtn.setOnAction(e -> refreshCdrAndStats());

    Button filterMineBtn = new Button("只看我");
    filterMineBtn.getStyleClass().setAll("btn-secondary");
    filterMineBtn.setOnAction(e -> fetchCdrHistory(user.getText().trim()));

    Button filterAllBtn = new Button("查看全部");
    filterAllBtn.getStyleClass().setAll("btn-secondary");
    filterAllBtn.setOnAction(e -> fetchCdrHistory(""));

    Label hintLabel = new Label("通话结束自动上报并持久化到后台 SQLite 数据库");
    hintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B;");

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    HBox actionsRow = new HBox(8, refreshCdrBtn, filterMineBtn, filterAllBtn, spacer, hintLabel);
    actionsRow.setAlignment(Pos.CENTER_LEFT);
    actionsRow.setPadding(new Insets(4, 0, 4, 0));

    cdrTableView = new TableView<>(cdrRecords);
    cdrTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    VBox.setVgrow(cdrTableView, Priority.ALWAYS);

    TableColumn<HttpFileClient.CdrRecord, Long> colId = new TableColumn<>("ID");
    colId.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().id()));
    colId.setMinWidth(45);
    colId.setPrefWidth(50);
    colId.setMaxWidth(60);

    TableColumn<HttpFileClient.CdrRecord, String> colCaller = new TableColumn<>("主叫");
    colCaller.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().caller()));
    colCaller.setMinWidth(75);
    colCaller.setPrefWidth(90);

    TableColumn<HttpFileClient.CdrRecord, String> colCallee = new TableColumn<>("被叫/房间");
    colCallee.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().calleeOrRoom()));
    colCallee.setMinWidth(90);
    colCallee.setPrefWidth(110);

    TableColumn<HttpFileClient.CdrRecord, String> colTime = new TableColumn<>("开始时间");
    colTime.setCellValueFactory(data -> {
      Instant t = data.getValue().startedAt();
      return new SimpleStringProperty(t != null ? t.toString().replace("T", " ").replace("Z", "") : "-");
    });
    colTime.setMinWidth(155);
    colTime.setPrefWidth(175);

    TableColumn<HttpFileClient.CdrRecord, String> colDur = new TableColumn<>("通话时长");
    colDur.setCellValueFactory(data -> {
      long d = data.getValue().durationSec();
      return new SimpleStringProperty(d > 0 ? (d + " 秒") : "0 秒 (未接通)");
    });
    colDur.setMinWidth(85);
    colDur.setPrefWidth(105);

    TableColumn<HttpFileClient.CdrRecord, Void> colActions = new TableColumn<>("快捷操作");
    colActions.setMinWidth(165);
    colActions.setPrefWidth(175);
    colActions.setMaxWidth(200);
    colActions.setCellFactory(param -> new TableCell<>() {
      private final Button chatBtn = new Button("聊天");
      private final Button audioBtn = new Button("语音");
      private final Button videoBtn = new Button("视频");
      private final HBox box = new HBox(4, chatBtn, audioBtn, videoBtn);
      {
        setAlignment(Pos.CENTER);
        box.setAlignment(Pos.CENTER);
        chatBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 4 2 4;");
        chatBtn.getStyleClass().setAll("btn-secondary", "action-btn-small");
        chatBtn.setMinWidth(46);
        chatBtn.setPrefWidth(46);
        chatBtn.setMaxWidth(46);
        chatBtn.setTooltip(new Tooltip("切换至消息聊天窗口"));

        audioBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 4 2 4;");
        audioBtn.getStyleClass().setAll("btn-secondary", "action-btn-small");
        audioBtn.setMinWidth(46);
        audioBtn.setPrefWidth(46);
        audioBtn.setMaxWidth(46);
        audioBtn.setTooltip(new Tooltip("发起语音呼叫"));

        videoBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 4 2 4;");
        videoBtn.getStyleClass().setAll("btn-secondary", "action-btn-small");
        videoBtn.setMinWidth(46);
        videoBtn.setPrefWidth(46);
        videoBtn.setMaxWidth(46);
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
        if (empty) {
          setGraphic(null);
        } else {
          videoBtn.setDisable(!sip.hasVideoSupport());
          setGraphic(box);
        }
      }
    });

    cdrTableView.getColumns().addAll(colId, colCaller, colCallee, colTime, colDur, colActions);

    VBox emptyPlaceholder = new VBox(10);
    emptyPlaceholder.setAlignment(Pos.CENTER);
    SVGPath emptyIcon = new SVGPath();
    emptyIcon.setContent(
        "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0"
            + " 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.127.96.361 1.903.7 2.81a2 2 0 0"
            + " 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.907.339 1.85.573"
            + " 2.81.7A2 2 0 0 1 22 16.92z");
    emptyIcon.setStyle(
        "-fx-fill: none; -fx-stroke: #94A3B8; -fx-stroke-width: 2; -fx-scale-x: 1.6; -fx-scale-y: 1.6;");
    Label emptyTitle = new Label("暂无通话记录");
    emptyTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #475569;");
    Label emptySub = new Label("通话结束后将自动显示并同步至后台 SQLite 数据库");
    emptySub.setStyle("-fx-font-size: 12px; -fx-text-fill: #94A3B8;");
    emptyPlaceholder.getChildren().addAll(emptyIcon, emptyTitle, emptySub);
    cdrTableView.setPlaceholder(emptyPlaceholder);

    cdrTableView.setRowFactory(tv -> {
      TableRow<HttpFileClient.CdrRecord> row = new TableRow<>();
      ContextMenu menu = new ContextMenu();

      MenuItem chatItem = new MenuItem("切换至消息聊天");
      chatItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          selectAndChatWith(row.getItem());
        }
      });

      MenuItem callAudioItem = new MenuItem("发起语音呼叫");
      callAudioItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          String target = getTargetFromCdr(row.getItem());
          peer.setText(target);
          run(() -> sip.call(target, domain.getText().trim(), false));
        }
      });

      MenuItem callVideoItem = new MenuItem("发起视频呼叫");
      callVideoItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          String target = getTargetFromCdr(row.getItem());
          peer.setText(target);
          run(() -> sip.call(target, domain.getText().trim(), true));
        }
      });

      MenuItem copyItem = new MenuItem("复制对方号码/房间");
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

  private VBox buildRoomPane() {
    HBox headerCard = new HBox(12);
    headerCard.setAlignment(Pos.CENTER_LEFT);
    headerCard.setPadding(new Insets(8, 12, 8, 12));
    headerCard.setStyle(
        "-fx-background-color: #f6f8fa; -fx-border-color: #d0d7de; -fx-border-radius: 6; -fx-background-radius: 6;");

    Label roomTitle = new Label("会议房间清单 (MCU 3×3 视频研讨)");
    roomTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #1E293B;");

    roomCountBadge = new Label("0 间");
    roomCountBadge.getStyleClass().setAll("badge-online");

    Region headerSpacer = new Region();
    HBox.setHgrow(headerSpacer, Priority.ALWAYS);

    Button refreshRoomBtn = new Button("刷新房间");
    refreshRoomBtn.getStyleClass().setAll("btn-secondary");
    refreshRoomBtn.setOnAction(e -> refreshRooms());

    Button createRoomBtn = new Button("新建研讨室...");
    createRoomBtn.getStyleClass().setAll("btn-primary");
    createRoomBtn.setOnAction(e -> showCreateRoomDialog());

    headerCard.getChildren().addAll(roomTitle, roomCountBadge, headerSpacer, refreshRoomBtn, createRoomBtn);

    inRoomBanner = new HBox(12);
    inRoomBanner.setAlignment(Pos.CENTER_LEFT);
    inRoomBanner.getStyleClass().setAll("active-room-banner");
    inRoomBanner.setVisible(false);
    inRoomBanner.setManaged(false);

    inRoomBannerLabel = new Label();
    inRoomBannerLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #166534;");
    HBox.setHgrow(inRoomBannerLabel, Priority.ALWAYS);

    inRoomExitBtn = new Button("退出当前会议");
    inRoomExitBtn.getStyleClass().setAll("btn-danger");
    inRoomExitBtn.setStyle(
        "-fx-font-size: 11px; -fx-padding: 4 12 4 12; -fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-weight: bold;");
    inRoomExitBtn.setTooltip(new Tooltip("断开与当前研讨室的连接并退出会议"));
    inRoomExitBtn.setOnAction(e -> run(sip::hangup));

    inRoomBanner.getChildren().addAll(inRoomBannerLabel, inRoomExitBtn);

    Label hintLabel = new Label("提示：房间标识由后台分配。点击【视频参会】拉起 3×3 MCU 混流画布；点击【语音参会】仅通话无视频弹窗。");
    hintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B; -fx-padding: 2 0 2 0;");
    hintLabel.setWrapText(true);
    roomHintLabel = hintLabel;

    roomTableView = new TableView<>(roomRecords);
    roomTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    VBox.setVgrow(roomTableView, Priority.ALWAYS);

    TableColumn<HttpFileClient.RoomInfo, Long> colId = new TableColumn<>("ID");
    colId.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().id()));
    colId.setMinWidth(45);
    colId.setMaxWidth(65);

    TableColumn<HttpFileClient.RoomInfo, String> colName = new TableColumn<>("房间名称");
    colName.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
    colName.setMinWidth(110);
    colName.setPrefWidth(140);

    TableColumn<HttpFileClient.RoomInfo, String> colUri = new TableColumn<>("SIP 标识 (URI)");
    colUri.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().sipUri()));
    colUri.setMinWidth(110);
    colUri.setPrefWidth(140);

    TableColumn<HttpFileClient.RoomInfo, String> colMax = new TableColumn<>("上限人数");
    colMax.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().maxMembers() + " 人"));
    colMax.setMinWidth(75);
    colMax.setMaxWidth(95);

    TableColumn<HttpFileClient.RoomInfo, String> colCount = new TableColumn<>("在会人数");
    colCount.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().memberCount() + " 人"));
    colCount.setMinWidth(75);
    colCount.setMaxWidth(95);

    TableColumn<HttpFileClient.RoomInfo, Void> colAction = new TableColumn<>("操作");
    colAction.setMinWidth(280);
    colAction.setMaxWidth(350);
    colAction.setPrefWidth(310);
    colAction.setCellFactory(param -> new RoomActionCell());

    roomTableView.getColumns().addAll(colId, colName, colUri, colMax, colCount, colAction);

    VBox emptyRoomPlaceholder = new VBox(10);
    emptyRoomPlaceholder.setAlignment(Pos.CENTER);
    SVGPath emptyRoomIcon = new SVGPath();
    emptyRoomIcon.setContent(
        "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M23 21v-2a4 4 0 0"
            + " 0-3-3.87 M16 3.13a4 4 0 0 1 0 7.75");
    emptyRoomIcon.setStyle(
        "-fx-fill: none; -fx-stroke: #94A3B8; -fx-stroke-width: 2; -fx-scale-x: 1.6; -fx-scale-y: 1.6;");
    Label emptyRoomTitle = new Label("暂无研讨室");
    emptyRoomTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #475569;");
    Label emptyRoomSub = new Label("可点击右上角【新建研讨室】或【刷新房间】");
    emptyRoomSub.setStyle("-fx-font-size: 12px; -fx-text-fill: #94A3B8;");
    emptyRoomPlaceholder.getChildren().addAll(emptyRoomIcon, emptyRoomTitle, emptyRoomSub);
    roomTableView.setPlaceholder(emptyRoomPlaceholder);
    roomTableView.setRowFactory(tv -> {
      TableRow<HttpFileClient.RoomInfo> row = new TableRow<>();
      ContextMenu menu = new ContextMenu();

      MenuItem exitItem = new MenuItem("离开/退出此会议");
      exitItem.setStyle("-fx-text-fill: #EF4444; -fx-font-weight: bold;");
      exitItem.setOnAction(e -> run(sip::hangup));

      MenuItem videoItem = new MenuItem("视频参会 (3×3 画面)");
      videoItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          HttpFileClient.RoomInfo room = row.getItem();
          if (room != null && room.sipUri() != null) {
            peer.setText(room.sipUri());
            run(() -> sip.call(room.sipUri(), domain.getText().trim(), true));
          }
        }
      });

      MenuItem audioItem = new MenuItem("语音参会 (纯音频)");
      audioItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          HttpFileClient.RoomInfo room = row.getItem();
          if (room != null && room.sipUri() != null) {
            peer.setText(room.sipUri());
            run(() -> sip.call(room.sipUri(), domain.getText().trim(), false));
          }
        }
      });

      MenuItem selectItem = new MenuItem("填入呼叫目标栏");
      selectItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          HttpFileClient.RoomInfo room = row.getItem();
          if (room != null && room.sipUri() != null) {
            peer.setText(room.sipUri());
            logs.append("操作", "已选定会议室目标: " + room.sipUri());
          }
        }
      });

      MenuItem copyItem = new MenuItem("复制房间 SIP 标识");
      copyItem.setOnAction(e -> {
        if (!row.isEmpty()) {
          HttpFileClient.RoomInfo room = row.getItem();
          if (room != null && room.sipUri() != null) {
            ClipboardContent content = new ClipboardContent();
            content.putString(room.sipUri());
            Clipboard.getSystemClipboard().setContent(content);
            logs.append("操作", "已复制到剪贴板：" + room.sipUri());
          }
        }
      });

      row.emptyProperty().addListener((obs, wasEmpty, isEmpty) -> {
        if (isEmpty) {
          row.setContextMenu(null);
        } else {
          row.setContextMenu(menu);
        }
      });

      row.setOnContextMenuRequested(event -> {
        if (!row.isEmpty()) {
          HttpFileClient.RoomInfo room = row.getItem();
          boolean isCur = room != null && sip.isCurrentRoom(room.sipUri());
          boolean ready = sip.isStarted() && sip.isRegistered() && !sip.isInCall();
          videoItem.setDisable(!ready);
          audioItem.setDisable(!ready);
          menu.getItems().clear();
          if (isCur) {
            menu.getItems().addAll(exitItem, selectItem, copyItem);
          } else {
            menu.getItems().addAll(videoItem, audioItem, selectItem, copyItem);
          }
        }
      });

      row.setOnMouseClicked(event -> {
        if (event.getClickCount() == 2 && (!row.isEmpty())) {
          HttpFileClient.RoomInfo room = row.getItem();
          peer.setText(room.sipUri());
          logs.append("操作", "已选定会议室: " + room.name() + " (" + room.sipUri() + ")");
        }
      });
      return row;
    });

    VBox roomBox = new VBox(8, headerCard, inRoomBanner, hintLabel, roomTableView);
    roomBox.setPadding(new Insets(10));
    roomBox.setStyle(
        "-fx-background-color: #ffffff; -fx-border-color: #d0d7de; -fx-border-radius: 8; -fx-background-radius: 8;");
    return roomBox;
  }

  class RoomActionCell extends TableCell<HttpFileClient.RoomInfo, Void> {
    final Button joinMainBtn = new Button("加入会议");
    final Button joinVideoBtn = new Button("视频");
    final Button joinAudioBtn = new Button("语音");
    final Button selectBtn = new Button("选定");
    final Button exitBtn = new Button("退出会议");
    final HBox defaultBox = new HBox(6, joinMainBtn, joinVideoBtn, joinAudioBtn, selectBtn);
    final HBox inMeetingBox = new HBox(6, exitBtn, selectBtn);

    {
      defaultBox.setAlignment(Pos.CENTER_LEFT);
      inMeetingBox.setAlignment(Pos.CENTER_LEFT);

      joinMainBtn.getStyleClass().setAll("btn-primary");
      joinMainBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-background-color: #2481CC; -fx-text-fill: white; -fx-font-weight: bold;");
      joinMainBtn.setTooltip(new Tooltip("一键进入研讨室并切换至聊天（智能根据设备开启音视频）"));
      joinMainBtn.setOnAction(e -> {
        HttpFileClient.RoomInfo room = getTableView().getItems().get(getIndex());
        if (room != null && room.sipUri() != null) {
          peer.setText(room.sipUri());
          boolean useVideo = sip.isVideoCallAllowed();
          String dom = (domain != null && domain.getText() != null) ? domain.getText().trim() : "lab";
          String blocked = roomJoinBlocker(useVideo);
          if (blocked != null) {
            blockRoomJoin(blocked);
            return;
          }
          if (!useVideo && videoReadyForCall) {
            noticeRoomJoin("摄像头未开启或没有可用的采集设备，本次以【语音】入会。需要画面请先打开摄像头再用【视频】入会。");
          }
          logs.append(
              "操作",
              "加入会议 " + room.sipUri() + "：" + (useVideo ? "视频入会（3×3 混流）" : "语音入会（纯音频）"));
          run(() -> sip.call(room.sipUri(), dom, useVideo));          if (rightTabPane != null && !rightTabPane.getTabs().isEmpty()) {
            rightTabPane.getSelectionModel().select(0);
          }
        }
      });

      joinVideoBtn.getStyleClass().setAll("btn-secondary");
      joinVideoBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8 4 8;");
      joinVideoBtn.setTooltip(new Tooltip("发起视频呼叫接入 FreeSWITCH MCU 会议室并拉起 3×3 混流画布"));
      joinVideoBtn.setOnAction(e -> {
        HttpFileClient.RoomInfo room = getTableView().getItems().get(getIndex());
        if (room != null && room.sipUri() != null) {
          peer.setText(room.sipUri());
          String dom = (domain != null && domain.getText() != null) ? domain.getText().trim() : "lab";
          String blocked = roomJoinBlocker(true);
          if (blocked != null) {
            blockRoomJoin(blocked);
            return;
          }
          logRoomAction("操作", "加入会议 " + room.sipUri() + "：视频入会（3×3 混流）");
          run(() -> sip.call(room.sipUri(), dom, true));
        }
      });

      joinAudioBtn.getStyleClass().setAll("btn-secondary");
      joinAudioBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8 4 8;");
      joinAudioBtn.setTooltip(new Tooltip("发起纯音频呼叫接入会议室（不开启视频，无黑框弹出）"));
      joinAudioBtn.setOnAction(e -> {
        HttpFileClient.RoomInfo room = getTableView().getItems().get(getIndex());
        if (room != null && room.sipUri() != null) {
          peer.setText(room.sipUri());
          String dom = (domain != null && domain.getText() != null) ? domain.getText().trim() : "lab";
          String blocked = roomJoinBlocker(false);
          if (blocked != null) {
            blockRoomJoin(blocked);
            return;
          }
          logRoomAction("操作", "加入会议 " + room.sipUri() + "：语音入会（纯音频）");
          run(() -> sip.call(room.sipUri(), dom, false));
        }
      });

      exitBtn.getStyleClass().setAll("btn-danger");
      exitBtn.setStyle(
          "-fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-weight: bold;");
      exitBtn.setTooltip(new Tooltip("断开与当前研讨室的连接并退出会议"));
      exitBtn.setOnAction(e -> run(sip::hangup));

      selectBtn.getStyleClass().setAll("btn-secondary");
      selectBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8 4 8;");
      selectBtn.setTooltip(new Tooltip("选定此会议室并切换至文字沟通"));
      selectBtn.setOnAction(e -> {
        HttpFileClient.RoomInfo room = getTableView().getItems().get(getIndex());
        if (room != null && room.sipUri() != null) {
          peer.setText(room.sipUri());
          logs.append("操作", "已选定会议室目标: " + room.sipUri());
          if (rightTabPane != null && !rightTabPane.getTabs().isEmpty()) {
            rightTabPane.getSelectionModel().select(0);
          }
        }
      });
    }

    @Override
    public void updateItem(Void item, boolean empty) {
      super.updateItem(item, empty);
      if (empty || getIndex() < 0 || getTableView() == null || getIndex() >= getTableView().getItems().size()) {
        setGraphic(null);
      } else {
        HttpFileClient.RoomInfo room = getTableView().getItems().get(getIndex());
        boolean isCurRoom = room != null && sip.isCurrentRoom(room.sipUri());
        if (isCurRoom) {
          setGraphic(inMeetingBox);
        } else {
          boolean inAnyCall = sip.isInCall();
          // 注意：这里不按「引擎/注册是否就绪」禁用按钮。
          // 禁用等于点了没反应，用户看不到任何原因；改为可点 + 点击后明确告知缺什么。
          joinMainBtn.setDisable(inAnyCall);
          joinMainBtn.setTooltip(new Tooltip(inAnyCall
              ? "当前已在通话/会议中，请先退出当前通话"
              : "一键进入研讨室并切换至聊天（当前将以"
                  + (sip.isVideoCallAllowed() ? "视频" : "语音") + "入会）"));
          joinVideoBtn.setDisable(inAnyCall);
          joinVideoBtn.setTooltip(new Tooltip(inAnyCall
              ? "当前已在通话/会议中，请先退出当前通话"
              : (videoReadyForCall
                  ? "发起视频呼叫接入 FreeSWITCH MCU 会议室并拉起 3×3 混流画布"
                  : "本机未检测到可用的视频能力，点击后会提示改用语音入会")));
          joinAudioBtn.setDisable(inAnyCall);
          joinAudioBtn.setTooltip(new Tooltip(inAnyCall
              ? "当前已在通话/会议中，请先退出当前通话"
              : "发起纯音频呼叫接入会议室（不开启视频，无黑框弹出）"));
          setGraphic(defaultBox);
        }
      }
    }
  }

  private void refreshRooms() {
    String httpUrl = httpServerField.getText().trim();
    httpFileClient.setBaseUrl(httpUrl);
    new Thread(
            () -> {
              try {
                List<HttpFileClient.RoomInfo> rooms = httpFileClient.fetchRooms();
                Platform.runLater(
                    () -> {
                      if (roomRefreshFailed) {
                        logs.append("系统", "拉取房间列表已恢复正常");
                        roomRefreshFailed = false;
                      }
                      roomRecords.setAll(rooms);
                      if (roomCountBadge != null) {
                        roomCountBadge.setText(rooms.size() + " 间");
                      }
                    });
              } catch (Exception ex) {
                Platform.runLater(
                    () -> {
                      if (!roomRefreshFailed) {
                        roomRefreshFailed = true;
                        logs.append("警告", "拉取房间列表失败: " + ex.getMessage());
                      }
                    });
              }
            },
            "room-list-fetcher")
        .start();
  }

  private void showCreateRoomDialog() {
    TextInputDialog dialog = new TextInputDialog("技术研讨室");
    dialog.setTitle("新建研讨室 (MCU)");
    dialog.setHeaderText("创建 FreeSWITCH MCU 视频会议室 (上限 8 人)");
    dialog.setContentText("请输入研讨室名称:");

    dialog.showAndWait().ifPresent(name -> {
      String trimmed = name.trim();
      if (trimmed.isEmpty()) return;
      new Thread(() -> {
        try {
          HttpFileClient.RoomInfo created = httpFileClient.createRoom(trimmed, 8);
          Platform.runLater(() -> {
            logs.append("系统", "研讨室创建成功: " + created.name() + " -> " + created.sipUri());
            peer.setText(created.sipUri());
            refreshRooms();
          });
        } catch (Exception ex) {
          Platform.runLater(() -> logs.append("错误", "创建房间失败: " + ex.getMessage()));
        }
      }, "create-room").start();
    });
  }

  private void showAvatarSettingsDialog() {
    if (assembler == null) return;
    assembler.showAvatarSettingsModal(
        primaryStage,
        httpFileClient,
        user.getText().trim(),
        user.getText().trim() + "@" + domain.getText().trim(),
        logs);
  }

  private void refreshContactAvatars() {
    new Thread(
        () -> {
          try {
            java.util.List<HttpFileClient.UserSummary> list = httpFileClient.fetchUsers();
            for (HttpFileClient.UserSummary us : list) {
              if (us.avatarUrl() != null && !us.avatarUrl().isBlank() && assembler != null) {
                assembler.updateContactAvatar(us.username(), httpFileClient.getBaseUrl() + us.avatarUrl());
              }
            }
          } catch (Exception ignored) {
          }
        },
        "contact-avatars-fetcher")
        .start();
  }

  private void run(Throwing action) {
    try {
      action.run();
    } catch (Throwable ex) {
      if (logs != null) {
        logs.append("错误", ex.getClass().getSimpleName() + "：" + ex.getMessage());
      }
    }
    if (startEngine != null) {
      refreshButtons();
    }
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

  static boolean isRoomTarget(String uri) {
    if (uri == null || uri.isBlank()) {
      return false;
    }
    String u = extractUser(uri);
    return u.startsWith("room-");
  }

  class ChatMessageCell extends ListCell<ChatMessage> {
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

    public ImageView getPreviewImageView() {
      return previewImageView;
    }

    ChatMessageCell() {
      metaLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
      statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #94A3B8;");
      textLabel.setWrapText(true);

      fileNameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #0F172A;");
      fileSizeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B;");
      previewImageView.setFitWidth(180);
      previewImageView.setPreserveRatio(true);
      previewImageView.setSmooth(true);
      previewImageView.setVisible(false);
      previewImageView.setManaged(false);
      previewImageView.setStyle(
          "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 4, 0, 0, 1);");

      fileBox.setStyle(
          "-fx-background-color: #FFFFFF; -fx-border-color: #E2E8F0; -fx-border-radius: 8;"
              + " -fx-background-radius: 8; -fx-padding: 8 10 8 10; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.03), 4, 0, 0, 1);");
      downloadBtn.getStyleClass().setAll("btn-secondary");
      openBtn.getStyleClass().setAll("btn-secondary");
      fileActionBox.getChildren().addAll(downloadBtn, openBtn);
      fileBox.getChildren().addAll(fileNameLabel, fileSizeLabel, previewImageView, fileActionBox);

      header.getChildren().addAll(metaLabel, statusLabel);
      root.getChildren().addAll(header, textLabel, fileBox);
      root.setPadding(new Insets(3, 6, 3, 6));
    }

    @Override
    public void updateItem(ChatMessage item, boolean empty) {
      super.updateItem(item, empty);
      if (empty || item == null) {
        setGraphic(null);
        return;
      }

      String time = item.formatTime();
      if (item.isOutgoing()) {
        metaLabel.setText("我 (" + item.getSender() + ")  " + time);
        metaLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #2481CC;");
        root.setAlignment(Pos.CENTER_RIGHT);
        header.setAlignment(Pos.CENTER_RIGHT);
        textLabel.getStyleClass().setAll("bubble-outgoing");
        textLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #0F172A;");
      } else {
        metaLabel.setText(item.getSender() + "  " + time);
        metaLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #475569;");
        metaLabel.setOnMouseClicked(e -> peer.setText(item.getSender()));
        metaLabel.setCursor(javafx.scene.Cursor.HAND);
        root.setAlignment(Pos.CENTER_LEFT);
        header.setAlignment(Pos.CENTER_LEFT);
        textLabel.getStyleClass().setAll("bubble-incoming");
        textLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #0F172A;");
      }

      statusLabel.setText(item.getStatus());
      if ("已送达".equals(item.getStatus())) {
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #10B981; -fx-font-weight: bold;");
      } else if (item.getStatus().startsWith("发送未送达") || "发送失败".equals(item.getStatus())) {
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #EF4444; -fx-font-weight: bold;");
      } else if ("待送达".equals(item.getStatus())) {
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #F59E0B; -fx-font-weight: bold;");
      } else {
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #94A3B8;");
      }

      if (item.isFile()) {
        textLabel.setVisible(false);
        textLabel.setManaged(false);
        fileBox.setVisible(true);
        fileBox.setManaged(true);

        FileMessage fm = item.getFile();
        String lower = fm.getName().toLowerCase();
        String icon = "[文档] ";
        boolean isImage = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
        if (isImage) {
          icon = "[图片] ";
        } else if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".m4a")) {
          icon = "[音频] ";
        } else if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") || lower.endsWith(".avi")) {
          icon = "[视频] ";
        } else if (lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".7z")) {
          icon = "[压缩包] ";
        }
        fileNameLabel.setText(icon + fm.getName());
        fileSizeLabel.setText(FileMessage.formatSize(fm.getSize()) + " · " + fm.getUrl());

        boolean hasLocal = item.getLocalPath() != null && Files.exists(item.getLocalPath());
        if (isImage) {
          try {
            Image img;
            if (hasLocal) {
              img = new Image(item.getLocalPath().toUri().toString(), 180, 0, true, true);
            } else {
              String normUrl = httpFileClient.normalizeFileUrl(fm.getUrl());
              img = new Image(normUrl, 180, 0, true, true, true);
            }
            previewImageView.setImage(img);
            previewImageView.setVisible(true);
            previewImageView.setManaged(true);
            previewImageView.setOnMouseClicked(
                e -> {
                  try {
                    if (hasLocal) {
                      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(item.getLocalPath().toFile());
                      } else {
                        getHostServices().showDocument(item.getLocalPath().toUri().toString());
                      }
                    } else {
                      getHostServices().showDocument(httpFileClient.normalizeFileUrl(fm.getUrl()));
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

  SipService getSip() {
    return sip;
  }

  Button getHangupBtn() {
    return hangup;
  }

  HBox getInRoomBanner() {
    return inRoomBanner;
  }

  Label getInRoomBannerLabel() {
    return inRoomBannerLabel;
  }

  Button getInRoomExitBtn() {
    return inRoomExitBtn;
  }

  Label getRoomHintLabel() {
    return roomHintLabel;
  }

  /**
   * 入会前的前置检查。返回 null 表示可以入会，否则返回要告诉用户的原因。
   * 之前这里是静默 return，点按钮没有任何反应，用户完全不知道缺什么。
   */
  private String roomJoinBlocker(boolean wantVideo) {
    if (!sip.isStarted()) {
      return "引擎还没启动，请先点【启动引擎】，等状态变成已注册后再入会。";
    }
    if (!sip.isRegistered()) {
      return "SIP 还没注册成功（当前状态：" + sip.statusText() + "），注册完成后才能加入会议。";
    }
    if (sip.isInCall()) {
      return "当前已经在通话/会议中，请先点【挂断】退出，再加入新的会议。";
    }
    if (wantVideo && !sip.hasVideoSupport()) {
      return "本机没有可用的视频能力（未启用 H.264 或没有视频设备），请改用【语音】入会。";
    }
    return null;
  }

  /** 把「为什么现在入不了会」同时写到提示行和控制台，避免点了没反应。 */
  private void blockRoomJoin(String reason) {
    noticeRoomJoin("暂时无法加入会议：" + reason);
    logRoomAction("警告", "入会被拒绝：" + reason);
  }

  private void noticeRoomJoin(String message) {
    roomHintNotice = message;
    roomHintNoticeUntil = System.currentTimeMillis() + 12000;
    if (roomHintLabel != null) {
      roomHintLabel.setText(message);
      roomHintLabel.setStyle(
          "-fx-font-size: 11px; -fx-text-fill: #B42318; -fx-font-weight: bold; -fx-padding: 2 0 2 0;");
    }
  }

  /** logs 只有在 start() 之后才存在；入会按钮在单元测试里也会被直接触发。 */
  private void logRoomAction(String category, String message) {
    if (logs != null) {
      logs.append(category, message);
    }
  }

  public TableView<HttpFileClient.RoomInfo> getRoomTableView() {
    if (roomTableView == null) {
      buildRoomPane();
    }
    return roomTableView;
  }

  public MainViewAssembler getAssembler() {
    return assembler;
  }

  public void setAssembler(MainViewAssembler assembler) {
    this.assembler = assembler;
  }

  public SipService getSipService() {
    return sip;
  }

  public TextField getPeerField() {
    return peer;
  }

  public ObservableList<HttpFileClient.RoomInfo> getRoomRecords() {
    return roomRecords;
  }

  public TabPane getRightTabPane() {
    return rightTabPane;
  }

  public HttpFileClient getHttpFileClient() {
    return httpFileClient;
  }

  public Tab getRoomTab() {
    return roomTab;
  }

  void syncUiState() {
    refreshButtons();
  }

  @Override
  public void stop() throws Exception {
    if (httpFileClient != null) {
      try {
        httpFileClient.logout();
      } catch (Throwable ignored) {
      }
    }
    if (sip != null) {
      try {
        sip.shutdown();
      } catch (Throwable ignored) {
      }
    }
    super.stop();
  }

  public static void main(String[] args) {
    launch(args);
  }
}
