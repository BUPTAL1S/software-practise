package lab.sip.client;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Explicit local bundle diagnostics. Never reads account preferences or tokens. */
public final class DeliveryDiagnostics {
  private DeliveryDiagnostics() {}

  static String buildId() {
    String id = System.getProperty("sip.build.id", "development");
    return id.matches("[a-zA-Z0-9._-]{1,80}") ? id : "development";
  }

  public static void main(String[] args) throws Exception {
    System.out.println("build=" + buildId());
    System.out.println("java=" + System.getProperty("java.version") + " os="
        + System.getProperty("os.name") + " arch=" + System.getProperty("os.arch"));
    NativeLoader.load();
    if (!WindowLayout.isAvailable() || !NativeMirror.isSupported()) {
      throw new IllegalStateException("Native layout/mirror helpers are missing or incompatible; rebuild the complete bundle");
    }
    var directory = NativeLoader.libraryFile().toAbsolutePath().getParent();
    try (var files = Files.list(directory)) {
      for (var file : files.filter(p -> p.getFileName().toString().endsWith(".dll")).sorted().toList()) {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
          byte[] buffer = new byte[65536]; int length;
          while ((length = stream.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        System.out.println(file.getFileName() + " bytes=" + Files.size(file)
            + " sha256=" + HexFormat.of().formatHex(digest.digest()));
      }
    }
    System.out.println("PASS: native bundle diagnostics");
  }
}
