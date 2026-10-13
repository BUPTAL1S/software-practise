package lab.sip.client;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata for a file shared over SIP MESSAGE.
 * The binary file is transferred via backend HTTP; the SIP MESSAGE
 * transports only this lightweight metadata tag.
 */
public final class FileMessage {
  private static final String PREFIX = "[FILE] ";
  private static final Pattern PIPE_PATTERN =
      Pattern.compile("^\\[FILE\\]\\s*([^|\\r\\n]+?)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(\\S+.*)$");
  private static final Pattern JSON_PATTERN =
      Pattern.compile("^\\[FILE\\]\\s*\\{.*\"name\"\\s*:\\s*\"([^\"]+)\".*\"size\"\\s*:\\s*(\\d+).*\"url\"\\s*:\\s*\"([^\"]+)\".*\\}$");

  private final String name;
  private final long size;
  private final String url;

  public FileMessage(String name, long size, String url) {
    this.name = Objects.requireNonNull(name, "name").trim();
    this.size = Math.max(0, size);
    this.url = Objects.requireNonNull(url, "url").trim();
  }

  public String getName() {
    return name;
  }

  public long getSize() {
    return size;
  }

  public String getUrl() {
    return url;
  }

  /**
   * Formats this file metadata as a standard text message body to be sent via SIP MESSAGE.
   */
  public String toWireFormat() {
    return PREFIX + name + " | " + size + " | " + url;
  }

  /**
   * Parses an incoming SIP MESSAGE body. Returns a FileMessage if the body
   * represents a file notification, or null if it is a normal text message.
   */
  public static FileMessage tryParse(String body) {
    if (body == null) {
      return null;
    }
    String trimmed = body.trim();
    if (!trimmed.startsWith("[FILE]")) {
      return null;
    }

    Matcher mPipe = PIPE_PATTERN.matcher(trimmed);
    if (mPipe.matches()) {
      try {
        String name = mPipe.group(1).trim();
        long size = Long.parseLong(mPipe.group(2).trim());
        String url = mPipe.group(3).trim();
        if (!name.isEmpty() && !url.isEmpty()) {
          return new FileMessage(name, size, url);
        }
      } catch (Exception ignored) {
      }
    }

    Matcher mJson = JSON_PATTERN.matcher(trimmed);
    if (mJson.matches()) {
      try {
        String name = mJson.group(1).trim();
        long size = Long.parseLong(mJson.group(2).trim());
        String url = mJson.group(3).trim();
        if (!name.isEmpty() && !url.isEmpty()) {
          return new FileMessage(name, size, url);
        }
      } catch (Exception ignored) {
      }
    }

    return null;
  }

  public static String formatSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    int exp = (int) (Math.log(bytes) / Math.log(1024));
    String pre = "KMGTPE".charAt(exp - 1) + "";
    return String.format(java.util.Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
  }

  @Override
  public String toString() {
    return "FileMessage{name='" + name + "', size=" + size + " (" + formatSize(size) + "), url='" + url + "'}";
  }
}
