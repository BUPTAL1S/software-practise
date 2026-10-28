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

  private void runProbe(String mode) throws Exception {
    Path output = Path.of("target", "camera-lifecycle-" + mode + ".log").toAbsolutePath();
    String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process child = new ProcessBuilder(javaBinary, "-cp", classpath, Probe.class.getName(), mode)
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(90, TimeUnit.SECONDS), "Camera probe timed out: " + output);
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
      SipService sip = new SipService(new SipService.Events() {
        public void log(String message) {}
        public void log(String category, String message) {}
        public void sip(boolean outgoing, String summary, String raw) {}
        public void stateChanged() {}
      });
      try (CallConnectedNotificationTest.Peer peer = new CallConnectedNotificationTest.Peer()) {
        sip.start(5098, "127.0.0.1");
        boolean inCall = args.length > 0 && args[0].equals("call");
        if (inCall) {
          sip.register("127.0.0.1:" + peer.socket.getLocalPort(), "1098", "", "lab", "udp");
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (!sip.isRegistered() && System.nanoTime() < deadline) Thread.sleep(20);
          if (!sip.isRegistered()) throw new AssertionError("Registration failed");
          sip.call("1099", "lab", true);
          if (!peer.invited.await(5, TimeUnit.SECONDS)) throw new AssertionError("INVITE missing");
          peer.answerWithVideo();
          deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (!sip.isCallConnected() && System.nanoTime() < deadline) Thread.sleep(20);
          if (!sip.isCallConnected()) throw new AssertionError("Video call did not connect");
        }
        Stage[] stage = new Stage[1];
        CountDownLatch shown = new CountDownLatch(1);
        Platform.runLater(() -> {
          stage[0] = new Stage();
          stage[0].setTitle("Camera lifecycle regression");
          stage[0].setScene(new Scene(new StackPane(), 480, 320));
          stage[0].show();
          shown.countDown();
        });
        if (!shown.await(5, TimeUnit.SECONDS)) throw new AssertionError("Stage did not open");
        for (int i = 0; i < 20; i++) {
          sip.setCameraEnabled(true);
          if (inCall) {
            sip.setCallVideoTransmit(true);
            sip.startCallPreview();
          } else {
            sip.startPreview();
          }
          long handle = sip.getPreviewWindowHandle();
          if (handle == 0) throw new AssertionError("No real camera preview");
          long remote = inCall ? sip.getActiveVideoWindowHandle() : handle;
          if (remote == 0 || (inCall && remote == handle)) {
            throw new AssertionError("Video call must have a separate receive renderer");
          }
          CountDownLatch stopped = new CountDownLatch(1);
          Platform.runLater(() -> {
            WindowLayout.attach(WindowLayout.findWindow(stage[0].getTitle()), remote,
                inCall ? handle : 0, 0, 0, 480, 320);
            // Production stop must detach itself; caller deliberately leaves it embedded.
            sip.stopPreview();
            sip.setCameraEnabled(false);
            if (inCall) sip.setCallVideoTransmit(false);
            stopped.countDown();
          });
          if (!stopped.await(5, TimeUnit.SECONDS)) {
            Thread.getAllStackTraces().forEach((thread, stack) -> {
              if (thread.getName().contains("JavaFX")) {
                System.err.println(thread.getName());
                for (var frame : stack) System.err.println(frame);
              }
            });
            throw new AssertionError("Embedded preview stop froze JavaFX at iteration " + i);
          }
          if (sip.isPreviewActive()) throw new AssertionError("Preview not stopped");
          System.out.println("Completed preview restart " + (i + 1));
        }
        if (inCall) {
          sip.hangup();
          peer.bye.await(2, TimeUnit.SECONDS);
        }
        sip.shutdown();
        System.out.println("PASS: 20 embedded preview restarts");
        System.exit(0);
      } catch (Throwable error) {
        error.printStackTrace();
        System.exit(1);
      }
    }
  }
}
