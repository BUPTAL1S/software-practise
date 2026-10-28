package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in hardware regression; isolated JVM prevents a native deadlock from freezing the suite. */
@EnabledIfSystemProperty(named = "sip.nativeVideoTest", matches = "true")
class CameraPreviewLifecycleTest {
  @Test void embeddedPreviewCanStopAndRestartTwentyTimes() throws Exception {
    runProbe("preview");
  }

  @Test void cameraCanToggleTwentyTimesDuringConnectedVideoCall() throws Exception {
    runProbe("call");
  }

  @Test void cameraCanRestartOnUiThreadWithRemoteVideoStillEmbedded() throws Exception {
    runProbe("call-ui");
  }

  @Test
  @EnabledIfSystemProperty(named = "sip.liveMcuTest", matches = "true")
  void cameraCanToggleTwentyTimesInsideRealConference() throws Exception {
    runProbe("room-ui");
  }

  @Test
  @EnabledIfSystemProperty(named = "sip.liveMcuTest", matches = "true")
  void mainApplicationCanToggleCameraWithConferenceDirectoryRefreshing() throws Exception {
    runProbe("app-room-ui");
  }

  private void runProbe(String mode) throws Exception {
    Path output = Path.of("target", "camera-lifecycle-" + mode + ".log").toAbsolutePath();
    String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process child = new ProcessBuilder(javaBinary, "-cp", classpath, Probe.class.getName(), mode)
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(mode.equals("app-room-ui") ? 150 : 90, TimeUnit.SECONDS), "Camera probe timed out: " + output);
      // Native PJSIP and Java can use different console encodings on Windows.
      String log = new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(0, child.exitValue(), log);
      assertTrue(log.contains("PASS: 20 embedded preview restarts"), log);
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  public static class Probe {
    public static void main(String[] args) throws Exception {
      Platform.startup(() -> {});
      Platform.setImplicitExit(false);
      if (args.length > 0 && args[0].equals("app-room-ui")) {
        try { runMainApplicationProbe(); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
        return;
      }
      CallPreviewLifecycle lifecycle = new CallPreviewLifecycle();
      SipService sip = new SipService(new SipService.Events() {
        public void log(String message) {}
        public void log(String category, String message) {
          if (category.equals("通话") || category.equals("警告") || category.equals("错误"))
            System.out.println(category + ": " + message);
        }
        public void sip(boolean outgoing, String summary, String raw) {
          System.out.println((outgoing ? "OUT " : "IN ") + summary);
        }
        public void stateChanged() {}
      });
      try (CallConnectedNotificationTest.Peer peer = new CallConnectedNotificationTest.Peer()) {
        sip.start(5098, "127.0.0.1");
        boolean inRoom = args.length > 0 && args[0].equals("room-ui");
        boolean inCall = inRoom || args.length > 0 && args[0].startsWith("call");
        boolean uiRestart = inRoom || args.length > 0 && args[0].equals("call-ui");
        String room = "room-camera-recovery-" + Long.toUnsignedString(System.nanoTime());
        HttpFileClient http = inRoom ? new HttpFileClient("http://127.0.0.1:8088") : null;
        if (inCall) {
          sip.register("127.0.0.1:" + (inRoom ? 5060 : peer.socket.getLocalPort()), "1098", "", "lab", "udp");
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (!sip.isRegistered() && System.nanoTime() < deadline) Thread.sleep(20);
          if (!sip.isRegistered()) throw new AssertionError("Registration failed");
          sip.call(inRoom ? room : "1099", "lab", true);
          if (!inRoom) {
            if (!peer.invited.await(5, TimeUnit.SECONDS)) throw new AssertionError("INVITE missing");
            peer.answerWithVideo();
          }
          deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (!sip.isCallConnected() && System.nanoTime() < deadline) Thread.sleep(20);
          if (!sip.isCallConnected()) throw new AssertionError("Video call did not connect");
          if (inRoom) System.out.println("Test-owned conference: " + room);
        }
        Stage[] stage = new Stage[1];
        VideoCallWindow[] window = new VideoCallWindow[1];
        CountDownLatch shown = new CountDownLatch(1);
        Platform.runLater(() -> {
          if (uiRestart) {
            window[0] = new VideoCallWindow(null);
            window[0].show("Camera UI lifecycle regression", true, true, () -> {}, value -> {}, value -> {});
          } else {
            stage[0] = new Stage();
            stage[0].setTitle("Camera lifecycle regression");
            stage[0].setScene(new Scene(new StackPane(), 480, 320));
            stage[0].show();
          }
          shown.countDown();
        });
        if (!shown.await(5, TimeUnit.SECONDS)) throw new AssertionError("Stage did not open");
        for (int i = 0; i < 20; i++) {
          sip.setCameraEnabled(true);
          if (inCall) {
            if (uiRestart) {
              CountDownLatch started = new CountDownLatch(1);
              java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
              Platform.runLater(() -> {
                lifecycle.changeCamera(sip, window[0], true,
                    () -> lifecycle.start(sip, window[0], started::countDown,
                        failure -> { error.set(failure); started.countDown(); }),
                    failure -> { error.set(failure); started.countDown(); });
              });
              if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("Preview restart froze JavaFX at iteration " + i);
              if (error.get() != null) throw new AssertionError(error.get());
            } else {
              sip.setCallVideoTransmit(true);
              sip.startCallPreview();
            }
          } else {
            sip.startPreview();
          }
          long handle = sip.getPreviewWindowHandle();
          if (handle == 0) throw new AssertionError("No real camera preview");
          long remote = inCall ? sip.getActiveVideoWindowHandle() : handle;
          if (remote == 0 || (inCall && remote == handle)) {
            throw new AssertionError("Video call must have a separate receive renderer: remote=" + remote
                + ", preview=" + handle + ", connected=" + sip.isCallConnected());
          }
          if (inRoom && !http.toggleRoomVmute(room, "1098", false)) throw new AssertionError("Conference unmute failed");
          CountDownLatch stopped = new CountDownLatch(1);
          Platform.runLater(() -> {
            if (uiRestart) window[0].attachNative(remote, handle);
            else WindowLayout.attach(WindowLayout.findWindow(stage[0].getTitle()), remote,
                inCall ? handle : 0, 0, 0, 480, 320);
            // Production stop must detach itself; caller deliberately leaves it embedded.
            if (uiRestart) {
              lifecycle.changeCamera(sip, window[0], false, stopped::countDown, error -> stopped.countDown());
            } else {
              sip.stopPreview();
              sip.setCameraEnabled(false);
              if (inCall) sip.setCallVideoTransmit(false);
              stopped.countDown();
            }
          });
          if (!stopped.await(5, TimeUnit.SECONDS)) {
            StringBuilder dump = new StringBuilder();
            Thread.getAllStackTraces().forEach((thread, stack) -> {
              dump.append(thread.getName()).append(' ').append(thread.getState()).append('\n');
              for (var frame : stack) dump.append("  ").append(frame).append('\n');
              if (thread.getName().contains("JavaFX")) {
                System.err.println(thread.getName());
                for (var frame : stack) System.err.println(frame);
              }
            });
            Files.writeString(Path.of("target/camera-lifecycle-threads.txt"), dump);
            throw new AssertionError("Embedded preview stop froze JavaFX at iteration " + i);
          }
          if (sip.isPreviewActive()) throw new AssertionError("Preview not stopped");
          if (inRoom && !http.toggleRoomVmute(room, "1098", true)) throw new AssertionError("Conference mute failed");
          System.out.println("Completed preview restart " + (i + 1));
        }
        if (inCall) {
          sip.hangup();
          if (!inRoom) peer.bye.await(2, TimeUnit.SECONDS);
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
          while (sip.isCallActive() && System.nanoTime() < deadline) Thread.sleep(20);
        }
        sip.shutdown();
        lifecycle.close();
        System.out.println("PASS: 20 embedded preview restarts");
        System.exit(0);
      } catch (Throwable error) {
        error.printStackTrace();
        System.exit(1);
      }
    }

    private static void runMainApplicationProbe() throws Exception {
      String room = "room-camera-app-" + Long.toUnsignedString(System.nanoTime());
      MainApp app = onFx(() -> {
        MainApp created = new MainApp();
        created.start(new Stage());
        ((javafx.scene.control.TextField) field(created, "user")).setText("1098");
        ((javafx.scene.control.TextField) field(created, "server")).setText("127.0.0.1");
        ((javafx.scene.control.TextField) field(created, "httpServerField")).setText("http://127.0.0.1:8088");
        created.getHttpFileClient().setBaseUrl("http://127.0.0.1:8088");
        created.getPeerField().setText(room);
        created.getRightTabPane().getSelectionModel().select(created.getRoomTab());
        return created;
      });
      SipService sip = app.getSipService();
      sip.start(5098, "127.0.0.1");
      sip.register("127.0.0.1:5060", "1098", "", "lab", "udp");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!sip.isRegistered() && System.nanoTime() < deadline) Thread.sleep(20);
      if (!sip.isRegistered()) throw new AssertionError("Test registration failed");
      // The app's call button runs on JavaFX; preserve that launch ordering.
      onFx(() -> { sip.call(room, "lab", true); return null; });
      System.out.println("Test-owned main-app conference: " + room);
      waitForCamera(app, true);
      for (int i = 0; i < 20; i++) {
        onFx(() -> { ((javafx.scene.control.CheckBox) field(app, "cameraToggleBox")).fire(); return null; });
        waitForCamera(app, false);
        onFx(() -> { ((javafx.scene.control.CheckBox) field(app, "cameraToggleBox")).fire(); return null; });
        waitForCamera(app, true);
        System.out.println("Completed main-app camera cycle " + (i + 1));
      }
      onFx(() -> { ((VideoCallWindow) field(field(app, "calls"), "videoCallWindow")).close(); return null; });
      sip.hangup();
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (sip.isCallActive() && System.nanoTime() < deadline) Thread.sleep(20);
      onFx(() -> { app.stop(); return null; });
      System.out.println("PASS: 20 embedded preview restarts (full MainApp with room refresh)");
    }

    private static void waitForCamera(MainApp app, boolean enabled) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
      while (System.nanoTime() < deadline) {
        boolean ready = onFx(() -> {
          Object calls = field(app, "calls");
          if (((CallPreviewLifecycle) field(calls, "callPreviews")).isBusy()) return false;
          SipService sip = app.getSipService();
          if (!sip.isCallConnected()) return false;
          app.syncUiState();
          if (((CallPreviewLifecycle) field(calls, "callPreviews")).isBusy()) return false;
          if (sip.isPreviewActive() != enabled) return false;
          VideoCallWindow window = (VideoCallWindow) field(calls, "videoCallWindow");
          return window != null && window.isShowing() && (!enabled
              || (sip.getPreviewWindowHandle() != 0 && sip.getActiveVideoWindowHandle() != 0));
        });
        if (ready) return;
        Thread.sleep(30);
      }
      throw new AssertionError("MainApp camera did not reach " + enabled);
    }

    private static Object field(Object target, String name) throws Exception {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> task) throws Exception {
      var future = new java.util.concurrent.FutureTask<T>(task);
      Platform.runLater(future);
      return future.get(6, TimeUnit.SECONDS);
    }
  }
}
