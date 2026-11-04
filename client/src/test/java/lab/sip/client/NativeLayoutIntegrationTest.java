package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.pjsip.pjsua2.*;

/** Two real SDL renderers fed by independent Colorbar devices; no camera or server needed. */
@EnabledIfSystemProperty(named="sip.nativeLayoutTest", matches="true")
class NativeLayoutIntegrationTest {
  @Test void maximizedAndContinuousResizeKeepIndependentRenderersInsideTheCallSurface() throws Exception {
    int soakSeconds = Math.max(0, Math.min(180, Integer.getInteger("sip.nativeLayoutSoakSeconds", 0)));
    Path log = Path.of("target/native-layout.log").toAbsolutePath();
    var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
        "-Dsip.captureDeviceName=Colorbar generator", "-Dsip.nativeLayoutSoakSeconds=" + soakSeconds,
        "-Dsip.native.dir=" + NativeLoader.libraryFile().toAbsolutePath().getParent(),
        "-cp", System.getProperty("surefire.test.class.path"), Probe.class.getName())
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(child.waitFor(60L + soakSeconds, TimeUnit.SECONDS), "Native layout probe timed out: " + log);
      String output = new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(0, child.exitValue(), output);
      assertTrue(output.contains("PASS: native fullscreen, resize, preview-only and mirror isolation"));
    } finally { if (child.isAlive()) child.destroyForcibly(); }
  }
  public static class Probe {
    static VideoCallWindow window;
    static Stage stage;
    static <T> T fx(Callable<T> work) throws Exception {
      FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task); return task.get(4, TimeUnit.SECONDS);
    }
    public static void main(String[] args) {
      try { run(); System.exit(0); } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
    static void run() throws Exception {
      Platform.startup(() -> {}); Platform.setImplicitExit(false);
      SipService sip = new SipService(new SipService.Events() {
        public void log(String text) {} public void log(String category, String text) { System.out.println(category+": "+text); }
        public void sip(boolean tx, String summary, String raw) {} public void stateChanged() {}
      });
      VideoPreview second = null;
      sip.start(5100, "127.0.0.1");
      System.out.println("LOADED: " + NativeLoader.libraryFile());
      try {
        sip.startPreview();
        long remote = sip.getPreviewWindowHandle();
        int firstId = sip.queryVideoDetails().captureDevId, secondId = -1;
        VidDevManager manager = Endpoint.instance().vidDevManager();
        for (int i=0; i<manager.getDevCount(); i++) {
          VideoDevInfo info=manager.getDevInfo(i);
          try { if (i != firstId && info.getName().toLowerCase().contains("colorbar")) secondId=i; }
          finally { info.delete(); }
        }
        assertTrue(secondId >= 0, "Pinned SDK must expose both Colorbar devices");
        second = new VideoPreview(secondId);
        VideoPreviewOpParam parameters = new VideoPreviewOpParam();
        parameters.setWindowFlags(2); // Same resizable renderer contract as SipService.
        try { second.start(parameters); } finally { parameters.delete(); }
        VideoWindow secondWindow=second.getVideoWindow();
        VideoWindowInfo info=secondWindow.getInfo();
        long pip;
        try { pip=info.getWinHandle().getHandle().getWindow(); } finally { info.delete(); secondWindow.delete(); }
        NativeMirror.restoreNormal(remote, "Synthetic remote");
        NativeMirror.applyPreviewMirror(pip, "Synthetic local preview");
        assertTrue(NativeMirror.isMirrored(pip)); assertFalse(NativeMirror.isMirrored(remote));
        assertTrue(WindowLayout.isAvailable(), "New native layout ABI must load");
        fx(() -> {
          window=new VideoCallWindow(null); window.show("本地合成视频布局验证", true, true, true, ()->{}, value->{}, value->{});
          var field=VideoCallWindow.class.getDeclaredField("stage"); field.setAccessible(true); stage=(Stage)field.get(window);
          stage.setMaximized(true); return null;
        });
        Thread.sleep(350);
        fx(() -> { window.attachNative(remote,pip); return null; });
        awaitPlacement(remote,pip); screenshot("maximized");
        fx(() -> { stage.setMaximized(false); return null; });
        for (int i=0; i<32; i++) {
          final int step=i;
          fx(() -> {
            stage.setWidth(760 + (step * 29) % 720); stage.setHeight(580 + (step * 19) % 370);
            window.attachNative(remote,pip); return null;
          });
          Thread.sleep(70);
        }
        awaitPlacement(remote,pip); screenshot("resized");
        int soakSeconds = Integer.getInteger("sip.nativeLayoutSoakSeconds", 0);
        long soakDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(soakSeconds);
        int resize = 0;
        while (System.nanoTime() < soakDeadline) {
          final int step = resize++;
          fx(() -> { stage.setWidth(800 + step % 4 * 150); stage.setHeight(600 + step % 3 * 90); return null; });
          awaitPlacement(remote, pip);
          Thread.sleep(1500);
        }
        if (soakSeconds > 0) {
          screenshot("soak");
          System.out.println("PASS: continuous native rendering " + soakSeconds + " seconds, " + resize + " settled resizes");
        }
        fx(() -> { window.setRemoteAvatarOff(true, "1002"); window.attachNative(0,pip); return null; });
        long hideDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (WindowLayout.describe(remote)[5] != 0 && System.nanoTime() < hideDeadline) Thread.sleep(50);
        Thread.sleep(350); // Allow the avatar's managed-state layout pulse to finish.
        awaitPlacement(0,pip);
        long[] local=WindowLayout.describe(pip);
        System.out.println("HIDDEN remote="+java.util.Arrays.toString(WindowLayout.describe(remote))+" pip="+java.util.Arrays.toString(local));
        assertEquals(6,local.length); assertNotEquals(0,local[0]); assertEquals(1,local[5]);
        assertEquals(0,WindowLayout.describe(remote)[5]); screenshot("peer-camera-off");
        // Parent removal happens while FX still pumps messages, before stopping SDL.
        WindowLayout.detach(remote,pip);
        NativeMirror.restoreNormal(pip, "Preview");
        second.stop(); second.delete(); second=null;
        sip.stopPreview();
        fx(() -> { window.closeWithoutDetach(); return null; });
        System.out.println("PASS: native fullscreen, resize, preview-only and mirror isolation");
      } finally { if (second != null) { second.stop(); second.delete(); } sip.shutdown(); Platform.exit(); }
    }
    static void check(long remote,long pip) {
      long[] main=WindowLayout.describe(remote), local=WindowLayout.describe(pip);
      System.out.println("GEOMETRY remote="+java.util.Arrays.toString(main)+" pip="+java.util.Arrays.toString(local));
      assertEquals(6,main.length); assertEquals(6,local.length);
      assertNotEquals(0,main[0]); assertEquals(main[0],local[0]);
      assertEquals(1,main[5]); assertEquals(1,local[5]);
      assertTrue(local[1]>=main[1] && local[2]>=main[2]);
      assertTrue(local[1]+local[3]<=main[1]+main[3] && local[2]+local[4]<=main[2]+main[4]);
      assertTrue(local[3]<main[3]/2 && local[4]<main[4]/2);
    }
    static void awaitPlacement(long remote, long pip) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      do {
        long[] main=WindowLayout.describe(remote), local=WindowLayout.describe(pip);
        var expected = fx(() -> {
          var field=VideoCallWindow.class.getDeclaredField("videoArea"); field.setAccessible(true);
          var pane=(javafx.scene.layout.StackPane)field.get(window);
          var area=pane.localToScene(pane.getLayoutBounds());
          return new NativeVideoPlacement.Bounds(0,remote,pip,(int)Math.round(area.getMinX()),(int)Math.round(area.getMinY()),
              (int)Math.round(area.getWidth()),(int)Math.round(area.getHeight()),stage.getOutputScaleX(),stage.getOutputScaleY());
        });
        if (local.length==6 && local[0]!=0 && local[5]==1) {
          int width=(int)Math.round(expected.w()*expected.scaleX()), height=(int)Math.round(expected.h()*expected.scaleY());
          int x=(int)Math.round(expected.x()*expected.scaleX()), y=(int)Math.round(expected.y()*expected.scaleY());
          int pipWidth=Math.min(width/4,(int)Math.round(280*expected.scaleX()));
          boolean remoteFits=remote==0 || (main.length==6 && main[0]==local[0] && main[5]==1
              && Math.abs(main[1]-x)<=1 && Math.abs(main[2]-y)<=1
              && Math.abs(main[3]-width)<=1 && Math.abs(main[4]-height)<=1);
          if (remoteFits && Math.abs(local[3]-pipWidth)<=1 && Math.abs(local[4]-pipWidth*9/16)<=1
              && Math.abs(local[1]+local[3]+Math.round(12*expected.scaleX())-x-width)<=1
              && Math.abs(local[2]+local[4]+Math.round(12*expected.scaleX())-y-height)<=1) {
            if (remote!=0) check(remote,pip);
            return;
          }
        }
        fx(() -> { window.attachNative(remote,pip); return null; });
        Thread.sleep(100);
      } while (System.nanoTime() < deadline);
      screenshot("failed");
      Thread.getAllStackTraces().forEach((thread, stack) -> {
        System.out.println(thread.getName()+" "+thread.getState());
        for (var frame:stack) System.out.println("  "+frame);
      });
      fail("Native renderer did not converge to the final requested viewport");
    }
    static void screenshot(String name) throws Exception {
      boolean previous = fx(() -> {
        boolean top = stage.isAlwaysOnTop(); stage.setAlwaysOnTop(true); stage.toFront(); return top;
      });
      try {
        Thread.sleep(250);
        var rect=fx(() -> {
          var root=stage.getScene().getRoot(); var area=root.localToScreen(root.getBoundsInLocal());
          // Capture only this window's client content, without desktop corners/titlebar.
          return new java.awt.Rectangle((int)Math.ceil(area.getMinX()),(int)Math.ceil(area.getMinY()),
              (int)Math.floor(area.getWidth()),(int)Math.floor(area.getHeight()));
        });
        Path path=Path.of("target/test-snapshots/native-layout-"+name+".png"); Files.createDirectories(path.getParent());
        javax.imageio.ImageIO.write(new java.awt.Robot().createScreenCapture(rect), "png", path.toFile());
        System.out.println("CAPTURE " + name + " " + rect + " " + fx(() -> {
          var field=VideoCallWindow.class.getDeclaredField("requestedBounds");field.setAccessible(true);
          var expected=(NativeVideoPlacement.Bounds)field.get(window);
          return expected + " actual=" + java.util.Arrays.toString(WindowLayout.describe(expected.pip()));
        }));
      } finally { fx(() -> { stage.setAlwaysOnTop(previous); return null; }); }
    }
  }
}
