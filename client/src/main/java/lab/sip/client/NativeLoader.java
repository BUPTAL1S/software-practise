package lab.sip.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Loads PJSUA2 JNI from native/windows-x64 or native/macos-arm64. */
final class NativeLoader {
  private NativeLoader() {}

  static void load() {
    Path lib = libraryFile();
    if (!Files.isRegularFile(lib)) {
      throw new IllegalStateException(
          "找不到 PJSUA2 原生库：\n"
              + lib
              + "\n请按 native/macos-arm64/BUILD.md 或 native/windows-x64/BUILD.md 编译后拷到该目录。");
    }
    System.load(lib.toAbsolutePath().toString());
  }

  static Path libraryFile() {
    String os = System.getProperty("os.name", "").toLowerCase();
    String arch = System.getProperty("os.arch", "").toLowerCase();
    Path root = findRepoRoot();
    if (os.contains("win")) {
      return root.resolve("native/windows-x64/pjsua2.dll");
    }
    if (os.contains("mac")) {
      Path arm = root.resolve("native/macos-arm64");
      Path intel = root.resolve("native/macos-x64");
      Path dir = (arch.contains("aarch64") || arch.contains("arm64")) ? arm : intel;
      Path dylib = dir.resolve("libpjsua2.dylib");
      Path jnilib = dir.resolve("libpjsua2.jnilib");
      if (Files.isRegularFile(dylib)) {
        return dylib;
      }
      return jnilib;
    }
    throw new IllegalStateException("不支持的系统：" + os + " " + arch);
  }

  static Path findRepoRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path p = dir; p != null; p = p.getParent()) {
      if (Files.isDirectory(p.resolve("native")) && Files.isRegularFile(p.resolve("README.md"))) {
        return p;
      }
    }
    Path parent = dir.getParent();
    if (parent != null && Files.isDirectory(parent.resolve("native"))) {
      return parent;
    }
    return dir;
  }
}
