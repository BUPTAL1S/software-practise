package lab.sip.client;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * An instant message in the chat timeline (either plain text or file notification).
 */
public final class ChatMessage {
  private static final DateTimeFormatter TIME_FMT =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
  private static final DateTimeFormatter DATE_FMT =
      DateTimeFormatter.ofPattern("yyyy年M月d日").withZone(ZoneId.systemDefault());

  private final String sender;
  private final String receiver;
  private final boolean outgoing;
  private final String text;
  private final FileMessage file;
  private final Instant timestamp;
  private volatile String status;
  private volatile Path localPath;
  private volatile Long backendId;

  public ChatMessage(
      String sender,
      String receiver,
      boolean outgoing,
      String text,
      FileMessage file,
      Instant timestamp,
      String status) {
    this.sender = Objects.requireNonNull(sender, "sender");
    this.receiver = Objects.requireNonNull(receiver, "receiver");
    this.outgoing = outgoing;
    this.text = text == null ? "" : text;
    this.file = file;
    this.timestamp = timestamp == null ? Instant.now() : timestamp;
    this.status = status == null ? "" : status;
  }

  public static ChatMessage text(String sender, String receiver, boolean outgoing, String text) {
    return new ChatMessage(sender, receiver, outgoing, text, null, Instant.now(), outgoing ? "已发送" : "");
  }

  public static ChatMessage file(String sender, String receiver, boolean outgoing, FileMessage file, Path localPath) {
    ChatMessage msg = new ChatMessage(sender, receiver, outgoing, file.toWireFormat(), file, Instant.now(), outgoing ? "已发送" : "");
    msg.setLocalPath(localPath);
    return msg;
  }

  public static ChatMessage fromServer(
      String sender, String receiver, boolean outgoing, String content, Instant timestamp, Long backendId, String status) {
    FileMessage fm = FileMessage.tryParse(content);
    ChatMessage msg = new ChatMessage(
        sender, receiver, outgoing, content, fm, timestamp, status == null ? "" : status);
    msg.backendId = backendId;
    return msg;
  }

  public String getSender() {
    return sender;
  }

  public String getReceiver() {
    return receiver;
  }

  public boolean isOutgoing() {
    return outgoing;
  }

  public String getText() {
    return text;
  }

  public boolean isFile() {
    return file != null;
  }

  public FileMessage getFile() {
    return file;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public String formatTime() {
    return TIME_FMT.format(timestamp);
  }

  public String formatDate() {
    return DATE_FMT.format(timestamp);
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Long getBackendId() {
    return backendId;
  }

  public void setBackendId(Long backendId) {
    this.backendId = backendId;
  }

  public Path getLocalPath() {
    return localPath;
  }

  public void setLocalPath(Path localPath) {
    this.localPath = localPath;
  }
}
