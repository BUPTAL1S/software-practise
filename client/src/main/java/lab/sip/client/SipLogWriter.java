package lab.sip.client;

import java.io.PrintWriter;
import org.pjsip.pjsua2.LogEntry;
import org.pjsip.pjsua2.LogWriter;

/** Bridges PJSUA2 log/SIP dumps to the UI events and an optional BYE observer. */
final class SipLogWriter extends LogWriter {
  interface ByeListener {
    void onByeResponse(int code, String reason);
  }

  private final SipEvents events;
  private final ByeListener byeListener;
  private PrintWriter fileSink;
  private boolean fileSinkInit;

  SipLogWriter(SipEvents events, ByeListener byeListener) {
    this.events = events;
    this.byeListener = byeListener;
  }

  @Override
  public void write(LogEntry entry) {
    try {
      String msg = entry.getMsg();
      if (msg == null || msg.isBlank()) {
        return;
      }
      mirrorToFile(msg);
      String trimmed = msg.trim();
      if (forwardSipDump(trimmed)) {
        return;
      }
      int level = entry.getLevel();
      if (level <= 1) {
        events.log("错误", trimmed);
      } else if (level == 2) {
        events.log("警告", trimmed);
      }
    } catch (Throwable ignored) {
      // Never throw into PJSUA2.
    }
  }

  private void mirrorToFile(String msg) {
    if (!fileSinkInit) {
      fileSinkInit = true;
      String path = System.getenv("UI_LOG_FILE");
      if (path != null && !path.isBlank()) {
        try {
          fileSink = new PrintWriter(new java.io.FileWriter(path, true), true);
        } catch (Throwable ignored) {
          fileSink = null;
        }
      }
    }
    if (fileSink != null) {
      fileSink.println(msg);
    }
  }

  private boolean forwardSipDump(String msg) {
    boolean tx = msg.contains("TX ");
    boolean rx = msg.contains("RX ");
    boolean dump =
        msg.contains(" bytes Request msg")
            || msg.contains(" bytes Response msg")
            || msg.contains("Request msg ")
            || msg.contains("Response msg ");
    if (!dump || (!tx && !rx)) {
      return false;
    }
    String summary = firstSipLine(msg);
    if (summary.isEmpty()) {
      summary = compactPjsipHeader(msg);
    }
    events.sip(tx, summary, msg);
    if (rx && msg.contains("Response msg ") && msg.contains("/BYE/") && summary.startsWith("SIP/2.0 ")) {
      String[] parts = summary.split("\\s+", 3);
      try {
        int code = Integer.parseInt(parts[1]);
        if (code >= 200 && byeListener != null) {
          byeListener.onByeResponse(code, parts.length > 2 ? parts[2] : "");
        }
      } catch (NumberFormatException ignored) {
        // not a status line
      }
    }
    return true;
  }

  private static String firstSipLine(String msg) {
    for (String line : msg.split("\\r?\\n")) {
      String t = line.trim();
      if (t.startsWith("SIP/2.0")
          || t.startsWith("INVITE ")
          || t.startsWith("ACK ")
          || t.startsWith("BYE ")
          || t.startsWith("CANCEL ")
          || t.startsWith("REGISTER ")
          || t.startsWith("OPTIONS ")
          || t.startsWith("UPDATE ")
          || t.startsWith("PRACK ")
          || t.startsWith("INFO ")
          || t.startsWith("MESSAGE ")
          || t.startsWith("NOTIFY ")
          || t.startsWith("SUBSCRIBE ")
          || t.startsWith("REFER ")) {
        return t;
      }
    }
    return "";
  }

  private static String compactPjsipHeader(String msg) {
    int nl = msg.indexOf('\n');
    String head = nl < 0 ? msg : msg.substring(0, nl);
    return head.replaceAll("\\s+", " ").trim();
  }
}
