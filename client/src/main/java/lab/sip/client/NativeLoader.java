package lab.sip.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Loads PJSUA2 JNI from native/windows-x64 or native/macos-arm64. */
public final class NativeLoader {
  private static boolean loaded = false;

  private NativeLoader() {}

  public static synchronized void load() {
    if (loaded) {
      return;
    }
    Path lib = libraryFile();
    if (!Files.isRegularFile(lib)) {
      throw new IllegalStateException(
          "找不到 PJSUA2 原生库：\n"
              + lib
              + "\n请按 native/macos-arm64/BUILD.md 或 native/windows-x64/BUILD.md 编译后拷到该目录。");
    }
    Path dir = lib.getParent();
    if (dir != null) {
      java.util.Set<Path> seen = new java.util.HashSet<>();
      if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
        // Windows LoadLibrary(abs) does not search the DLL folder for dependents.
        for (String dep :
            new String[] {
              "libwinpthread-1.dll",
              "libgcc_s_seh-1.dll",
              "libssp-0.dll",
              "libstdc++-6.dll",
              "openh264.dll",
              "libopenh264.dll",
              "SDL2.dll"
            }) {
          Path p = dir.resolve(dep);
          if (Files.isRegularFile(p)) {
            try {
              Path real = p.toRealPath();
              if (seen.add(real)) {
                System.load(real.toString());
              }
            } catch (Throwable ignored) {
            }
          }
        }
      } else {
        // macOS preloads openh264/SDL2 if bundled
        for (String dep :
            new String[] {
              "libopenh264.dylib",
              "libopenh264.8.dylib",
              "libopenh264.jnilib",
              "libSDL2-2.0.0.dylib",
              "libSDL2.dylib"
            }) {
          Path p = dir.resolve(dep);
          if (Files.isRegularFile(p)) {
            try {
              Path real = p.toRealPath();
              if (seen.add(real)) {
                System.load(real.toString());
              }
            } catch (Throwable ignored) {
            }
          }
        }
      }
    }
    System.load(lib.toAbsolutePath().toString());
    loaded = true;
  }

  static Path libraryFile() {
    String os = System.getProperty("os.name", "").toLowerCase();
    String arch = System.getProperty("os.arch", "").toLowerCase();
    Path root = findRepoRoot();

    // 1. Direct explicit property override
    String explicitDir = System.getProperty("sip.native.dir");
    if (explicitDir != null && !explicitDir.isBlank()) {
      Path p = Path.of(explicitDir).toAbsolutePath();
      if (Files.isDirectory(p)) {
        Path cand = resolveLibInDir(p, os);
        if (cand != null) return cand;
      }
    }

    if (os.contains("win")) {
      Path dll1 = root.resolve("native/windows-x64/pjsua2.dll");
      if (Files.isRegularFile(dll1)) return dll1;
      Path dll2 = root.resolve("native/pjsua2.dll");
      if (Files.isRegularFile(dll2)) return dll2;
      return dll1;
    }
    if (os.contains("mac")) {
      Path arm = root.resolve("native/macos-arm64");
      Path intel = root.resolve("native/macos-x64");
      Path dir = (arch.contains("aarch64") || arch.contains("arm64")) ? arm : intel;
      Path cand = resolveLibInDir(dir, os);
      if (cand != null) return cand;

      Path flatCand = resolveLibInDir(root.resolve("native"), os);
      if (flatCand != null) return flatCand;

      Path dylib = dir.resolve("libpjsua2.dylib");
      return dylib;
    }
    throw new IllegalStateException("不支持的系统：" + os + " " + arch);
  }

  private static Path resolveLibInDir(Path dir, String os) {
    if (dir == null || !Files.isDirectory(dir)) return null;
    if (os.contains("win")) {
      Path dll = dir.resolve("pjsua2.dll");
      if (Files.isRegularFile(dll)) return dll;
    } else if (os.contains("mac")) {
      Path dylib = dir.resolve("libpjsua2.dylib");
      if (Files.isRegularFile(dylib)) return dylib;
      Path jnilib = dir.resolve("libpjsua2.jnilib");
      if (Files.isRegularFile(jnilib)) return jnilib;
    }
    return null;
  }

  public static Path findRepoRoot() {
    // 1. Check user.dir
    Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    if (Files.isDirectory(userDir.resolve("native"))) {
      return userDir;
    }

    // 2. Check code source (where the JAR or classes reside)
    try {
      java.net.URL location = NativeLoader.class.getProtectionDomain().getCodeSource().getLocation();
      if (location != null) {
        Path codePath = Path.of(location.toURI()).toAbsolutePath();
        Path codeDir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
        for (Path p = codeDir; p != null; p = p.getParent()) {
          if (Files.isDirectory(p.resolve("native"))) {
            return p;
          }
        }
      }
    } catch (Exception ignored) {
    }

    // 3. Walk up userDir searching for native/ and README.md (dev repository mode)
    for (Path p = userDir; p != null; p = p.getParent()) {
      if (Files.isDirectory(p.resolve("native")) && Files.isRegularFile(p.resolve("README.md"))) {
        return p;
      }
      if (Files.isDirectory(p.resolve("native"))) {
        return p;
      }
    }

    Path parent = userDir.getParent();
    if (parent != null && Files.isDirectory(parent.resolve("native"))) {
      return parent;
    }
    return userDir;
  }

  public static void main(String[] args) {
    System.out.println("[NativeLoader] Testing native library loading in standalone JVM...");
    load();
    System.out.println("[NativeLoader] Success: PJSUA2 native library loaded successfully (" + libraryFile() + ")");
  }
}
