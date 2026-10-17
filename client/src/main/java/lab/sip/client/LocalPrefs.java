package lab.sip.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Local-only SIP proxy + transport. Do not commit client/.local-prefs. */
final class LocalPrefs {
  private LocalPrefs() {}

  static String server = "";
  static String transport = "tcp";
  static String httpServer = "http://localhost:8088";

  static void load() {
    Path file = file();
    if (!Files.isRegularFile(file)) {
      return;
    }
    Properties props = new Properties();
    try (var in = Files.newInputStream(file)) {
      props.load(in);
    } catch (IOException ignored) {
      return;
    }
    server = props.getProperty("sip.server", "").trim();
    transport = "tcp";
    String h = props.getProperty("http.server", "").trim();
    if (!h.isEmpty()) {
      httpServer = h;
    }
  }

  static void save(String sipServer, String sipTransport, String httpUrl) {
    Properties props = new Properties();
    props.setProperty("sip.server", sipServer == null ? "" : sipServer.trim());
    props.setProperty("sip.transport", "tcp");
    props.setProperty("http.server", httpUrl == null ? "http://localhost:8088" : httpUrl.trim());
    Path file = file();
    try {
      Files.createDirectories(file.getParent());
      try (var out = Files.newOutputStream(file)) {
        props.store(out, "SIP lab local prefs — gitignored");
      }
      server = props.getProperty("sip.server");
      transport = props.getProperty("sip.transport");
      httpServer = props.getProperty("http.server");
    } catch (IOException ignored) {
      // local convenience only
    }
  }

  private static Path file() {
    return NativeLoader.findRepoRoot().resolve("client/.local-prefs");
  }
}
