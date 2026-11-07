package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.pjsip.pjsua2.*;

/** Hidden Colorbar only: no JavaFX windows, camera, microphone, account or server. */
@EnabledIfSystemProperty(named="sip.nativeIdleTest", matches="true")
class SdlIdleEventIntegrationTest {
  @Test void sdlOwnerStillPumpsMessagesAfterLastRendererStops() throws Exception {
    Path log=Path.of("target/native-idle-events.log").toAbsolutePath();
    String javaBin = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    Process child=new ProcessBuilder(javaBin,
        "-Dsip.native.dir="+System.getProperty("sip.native.dir"),
        "-Dsip.idleProbe="+System.getProperty("sip.idleProbe"),
        "-cp",System.getProperty("surefire.test.class.path"),Probe.class.getName())
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(child.waitFor(45,TimeUnit.SECONDS),"SDL lifecycle timed out: "+log);
      // Native device names can use the Windows code page. Preserve the failure
      // instead of masking it with a strict UTF-8 decoding exception.
      assertEquals(0,child.exitValue(),new String(Files.readAllBytes(log),java.nio.charset.StandardCharsets.UTF_8));
    } finally { if(child.isAlive()) child.destroyForcibly().waitFor(5,TimeUnit.SECONDS); }
  }

  public static class Probe {
    public static void main(String[] args) throws Exception {
      NativeLoader.load(); System.load(System.getProperty("sip.idleProbe"));
      Endpoint ep=new Endpoint(); ep.libCreate();
      try {
        EpConfig config=new EpConfig();
        try {config.getMedConfig().setVidPreviewEnableNative(false);ep.libInit(config);}finally{config.delete();}
        ep.libStart();
        VidDevManager devices=ep.vidDevManager();int capture=-1,renderer=-1;
        for(int i=0;i<devices.getDevCount();i++) {
          VideoDevInfo info=devices.getDevInfo(i);
          try {
            if(info.getName().toLowerCase().contains("colorbar"))capture=i;
            if(info.getDriver().equalsIgnoreCase("SDL"))renderer=i;
          } finally {info.delete();}
        }
        assertTrue(capture>=0 && renderer>=0,"Pinned SDK must expose Colorbar and SDL");
        for(int cycle=0;cycle<5;cycle++) {
          VideoPreview preview=new VideoPreview(capture);boolean started=false;
          try {
            VideoPreviewOpParam param=new VideoPreviewOpParam();
            try {param.setShow(false);param.setRendId(renderer);preview.start(param);started=true;}finally{param.delete();}
            VideoWindow window=preview.getVideoWindow();VideoWindowInfo info=window.getInfo();
            long handle=info.getWinHandle().getHandle().getWindow();
            try {
              // Hidden previews are not automatically connected. Start the local
              // conference clock so queued port removals actually destroy SDL.
              VideoMedia source=preview.getVideoMedia(),sink=window.getVideoMedia();
              VideoMediaTransmitParam transmit=new VideoMediaTransmitParam();
              try {source.startTransmit(sink,transmit);}finally{transmit.delete();source.delete();sink.delete();}
              assertTrue(SdlEventProbe.begin(handle),"Install own-thread heartbeat observer");
            }
            finally {info.delete();window.delete();}
            assertTrue(SdlEventProbe.heartbeat(1500),"Positive control: active SDL owner must pump");
            preview.stop();started=false;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(SdlEventProbe.windowExists(handle) && System.nanoTime()<deadline) Thread.sleep(20);
            assertFalse(SdlEventProbe.windowExists(handle),"Renderer must be destroyed before testing idle delivery");
            Thread.sleep(300); // Beyond the existing 50 ms event cadence.
            assertTrue(SdlEventProbe.heartbeat(1500),"Idle SDL owner stopped pumping after final renderer destruction, cycle="+cycle);
            System.out.println("PASS: active and idle SDL heartbeats, cycle="+cycle);
          } finally {SdlEventProbe.end();if(started)preview.stop();preview.delete();}
        }
        var before=ProcessHandle.current().info().totalCpuDuration().orElseThrow();
        Thread.sleep(2000);
        var after=ProcessHandle.current().info().totalCpuDuration().orElseThrow();
        System.out.println("IDLE CPU: "+after.minus(before).toMillis()+" ms process CPU / 2000 ms wall time");
      } finally {ep.libDestroy();ep.delete();}
      System.out.println("PASS: idle message delivery and endpoint shutdown");
    }
  }
}

final class SdlEventProbe {
  static native boolean windowExists(long ownWindow);
  static native boolean begin(long ownWindow);
  static native boolean heartbeat(int timeoutMillis);
  static native void end();
}
