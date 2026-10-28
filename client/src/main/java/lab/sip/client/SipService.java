package lab.sip.client;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.AccountConfig;
import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.AuthCredInfo;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallMediaInfo;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.CallVidSetStreamParam;
import org.pjsip.pjsua2.pjsua_call_vid_strm_op;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.EpConfig;
import org.pjsip.pjsua2.LogEntry;
import org.pjsip.pjsua2.LogWriter;
import org.pjsip.pjsua2.OnCallMediaStateParam;
import org.pjsip.pjsua2.OnCallStateParam;
import org.pjsip.pjsua2.OnIncomingCallParam;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.CodecInfo;
import org.pjsip.pjsua2.CodecInfoVector2;
import org.pjsip.pjsua2.MediaSize;
import org.pjsip.pjsua2.StringVector;
import org.pjsip.pjsua2.TransportConfig;
import org.pjsip.pjsua2.VidDevManager;
import org.pjsip.pjsua2.VideoDevInfo;
import org.pjsip.pjsua2.VideoPreview;
import org.pjsip.pjsua2.VideoPreviewOpParam;
import org.pjsip.pjsua2.VideoWindow;
import org.pjsip.pjsua2.VideoWindowInfo;
import org.pjsip.pjsua2.pj_log_decoration;
import java.util.HashMap;
import java.util.Map;
import org.pjsip.pjsua2.Buddy;
import org.pjsip.pjsua2.BuddyConfig;
import org.pjsip.pjsua2.OnInstantMessageParam;
import org.pjsip.pjsua2.OnInstantMessageStatusParam;
import org.pjsip.pjsua2.SendInstantMessageParam;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsip_inv_state;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsip_transport_type_e;
import org.pjsip.pjsua2.pjsua_call_media_status;

/** PJSUA2 wrapper: REGISTER + audio and video + instant messaging. */
final class SipService {
  static final String VIDEO_AVATAR_OFF = "[VIDEO_AVATAR_OFF]";
  static final String VIDEO_AVATAR_ON = "[VIDEO_AVATAR_ON]";

  static boolean isVideoAvatarControl(String body) {
    return VIDEO_AVATAR_OFF.equals(body) || VIDEO_AVATAR_ON.equals(body);
  }

  interface Events {
    void log(String msg);

    void log(String category, String msg);

    void sip(boolean outgoing, String summary, String raw);

    void stateChanged();

    default void instantMessageReceived(String fromUri, String toUri, String body, String contentType) {}

    default void instantMessageStatus(String toUri, String body, int statusCode, String reason) {}

    default void videoAvatarChanged(String fromUri, boolean off) {}

    default void callEnded(
        String callId, String caller, String calleeOrRoom, java.time.Instant startedAt, long durationSec, boolean wasConnected) {
      callEnded(caller, calleeOrRoom, startedAt, durationSec, wasConnected);
    }

    default void callEnded(
        String caller, String calleeOrRoom, java.time.Instant startedAt, long durationSec, boolean wasConnected) {}
  }


  private final Events events;
  private final ScheduledExecutorService hangupWatch =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "sip-hangup-watch");
            t.setDaemon(true);
            return t;
          });
  private Endpoint endpoint;
  private ClientLogWriter logWriter;
  private ClientAccount account;
  private ClientCall activeCall;
  /** 最近一次创建的通话包装对象；关机时必须显式 delete，否则 finalize 会在库销毁后断言。 */
  private volatile ClientCall lastCall;
  private VideoPreview videoPreview;
  private AudioMedia callAudioMedia;
  private AudioMedia captureAudioMedia;
  private volatile boolean audioMuted;
  private boolean started;
  private boolean incomingRinging;
  private int boundPort;
  private String advertisedIp = "";
  private String outboundProxy = "";
  private int lastRegCode;
  private int hangupWatchSeq;
  private boolean byeAnswered;
  private ScheduledFuture<?> hangupWatchTask;
  private final Map<String, Buddy> buddies = new HashMap<>();

  SipService(Events events) {
    this.events = events;
  }

  synchronized boolean isStarted() {
    return started;
  }

  synchronized boolean isRegistered() {
    return started && account != null && lastRegCode == pjsip_status_code.PJSIP_SC_OK;
  }

  static final class VideoDetails {
    final boolean supported;
    final String codec;
    final String captureDev;
    final String renderDev;
    final int captureDevId;
    final int renderDevId;

    VideoDetails(
        boolean supported,
        String codec,
        String captureDev,
        String renderDev,
        int capId,
        int renId) {
      this.supported = supported;
      this.codec = codec;
      this.captureDev = captureDev;
      this.renderDev = renderDev;
      this.captureDevId = capId;
      this.renderDevId = renId;
    }
  }

  private void ensureThreadRegistered() {
    if (started && endpoint != null) {
      try {
        if (!endpoint.libIsThreadRegistered()) {
          String name = Thread.currentThread().getName();
          if (name == null || name.isBlank()) {
            name = "sip-th-" + Thread.currentThread().getId();
          } else if (name.length() > 28) {
            name = name.substring(0, 28);
          }
          endpoint.libRegisterThread(name);
        }
      } catch (Throwable ignored) {
      }
    }
  }

  VideoDetails queryVideoDetails() {
    synchronized (this) {
      if (!started || endpoint == null) {
        return new VideoDetails(false, "引擎未启动", "无", "无", -1, -1);
      }
      ensureThreadRegistered();
      try {
        String foundCodec = "";
        CodecInfoVector2 codecs = endpoint.videoCodecEnum2();
        for (int i = 0; i < (int) codecs.size(); i++) {
          CodecInfo ci = codecs.get(i);
          if (ci.getPriority() > 0) {
            String cid = ci.getCodecId();
            if (foundCodec.isEmpty()) {
              foundCodec = cid;
            } else {
              foundCodec += ", " + cid;
            }
          }
        }
        if (foundCodec.isEmpty()) {
          return new VideoDetails(false, "无活跃编解码器", "无", "无", -1, -1);
        }

        VidDevManager mgr = endpoint.vidDevManager();
        int devCount = (int) mgr.getDevCount();
        String capName = "无可用采集设备";
        int capId = -1;
        String renName = "系统默认渲染器";
        int renId = -1;

        int fallbackCap = -1;
        String fallbackCapName = "";

        for (int i = 0; i < devCount; i++) {
          VideoDevInfo info = mgr.getDevInfo(i);
          int dir = info.getDir();
          if (dir == 1 || dir == 3) {
            String name = info.getName();
            if (!name.toLowerCase().contains("colorbar") && capId < 0) {
              capId = i;
              capName = name;
            }
            if (fallbackCap < 0) {
              fallbackCap = i;
              fallbackCapName = name;
            }
          }
          if ((dir == 2 || dir == 3) && renId < 0) {
            renId = i;
            renName = info.getName();
          }
        }

        if (capId < 0 && fallbackCap >= 0) {
          capId = fallbackCap;
          capName = fallbackCapName;
        }

        return new VideoDetails(true, foundCodec, capName, renName, capId, renId);
      } catch (Throwable t) {
        return new VideoDetails(false, "阶段1纯音频模式", "无", "无", -1, -1);
      }
    }
  }

  boolean hasVideoSupport() {
    return queryVideoDetails().supported;
  }

  boolean hasCaptureDevice() {
    return queryVideoDetails().captureDevId >= 0;
  }

  int findDefaultCaptureDev() {
    return queryVideoDetails().captureDevId;
  }

  private volatile boolean cameraEnabled = true;

  public boolean isCameraEnabled() {
    return cameraEnabled;
  }

  public void setCameraEnabled(boolean enabled) {
    this.cameraEnabled = enabled;
  }

  public synchronized void setCallVideoTransmit(boolean transmit) {
    if (activeCall != null) {
      activeCall.setTransmitVideo(transmit);
    }
  }

  public synchronized boolean isCallActive() {
    return activeCall != null;
  }

  /** True only after the INVITE has been confirmed. */
  public synchronized boolean isCallConnected() {
    if (activeCall == null) {
      return false;
    }
    try {
      ensureThreadRegistered();
      return activeCall.getInfo().getState() == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED;
    } catch (Throwable ignored) {
      return false;
    }
  }

  public boolean isAudioMuted() {
    return audioMuted;
  }

  public synchronized void setAudioMuted(boolean muted) {
    this.audioMuted = muted;
    if (callAudioMedia != null && captureAudioMedia != null) {
      try {
        ensureThreadRegistered();
        if (muted) {
          captureAudioMedia.stopTransmit(callAudioMedia);
        } else {
          captureAudioMedia.startTransmit(callAudioMedia);
        }
        events.log("通话", muted ? "麦克风已静音" : "麦克风已恢复");
      } catch (Throwable t) {
        events.log("警告", "切换静音失败: " + t.getMessage());
      }
    }
    events.stateChanged();
  }

  /** Native handle of the remote PJSIP video window (HWND on Windows, NSWindow on macOS). */
  public synchronized long getActiveVideoWindowHandle() {
    if (activeCall == null || !isCallConnected()) {
      return 0;
    }
    try {
      ensureThreadRegistered();
    } catch (Throwable ignored) {
    }
    return nativeVideoWindowHandle(activeCall.getActiveVideoWindow());
  }

  /** Native handle of the PJSIP camera preview window. */
  public synchronized long getPreviewWindowHandle() {
    if (videoPreview == null) {
      return 0;
    }
    try {
      ensureThreadRegistered();
      return nativeVideoWindowHandle(videoPreview.getVideoWindow());
    } catch (Throwable ignored) {
      return 0;
    }
  }

  private static long nativeVideoWindowHandle(VideoWindow win) {
    if (win == null) {
      return 0;
    }
    VideoWindowInfo info = null;
    try {
      info = win.getInfo();
      if (info == null || info.getWinHandle() == null || info.getWinHandle().getHandle() == null) {
        return 0;
      }
      return info.getWinHandle().getHandle().getWindow();
    } catch (Throwable ignored) {
      return 0;
    } finally {
      if (info != null) {
        try {
          info.delete();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  public synchronized String getActiveCallRemoteUri() {
    if (activeCall != null) {
      try {
        CallInfo ci = activeCall.getInfo();
        return ci.getRemoteUri();
      } catch (Throwable ignored) {
      }
    }
    return "";
  }

  public boolean isVideoCallAllowed() {
    return hasVideoSupport() && hasCaptureDevice() && cameraEnabled;
  }

  private volatile boolean previewMirrorEnabled = true;

  synchronized boolean isPreviewActive() {
    return videoPreview != null;
  }

  synchronized boolean isPreviewMirrorEnabled() {
    return previewMirrorEnabled;
  }

  synchronized void togglePreviewMirror() {
    this.previewMirrorEnabled = !this.previewMirrorEnabled;
    if (videoPreview != null) {
      ensureThreadRegistered();
      VideoWindow win = null;
      try {
        win = videoPreview.getVideoWindow();
        if (win != null) {
          VideoWindowInfo winInfo = null;
          try {
            winInfo = win.getInfo();
            if (winInfo != null && winInfo.getWinHandle() != null
                && winInfo.getWinHandle().getHandle() != null) {
              long hwnd = winInfo.getWinHandle().getHandle().getWindow();
              if (previewMirrorEnabled) {
                NativeMirror.applyPreviewMirror(hwnd, "摄像头预览 (镜像)");
                events.log("系统", "摄像头预览已切换为：镜像模式 (符合照镜子直觉)");
              } else {
                NativeMirror.restoreNormal(hwnd, "摄像头预览 (真实)");
                events.log("系统", "摄像头预览已切换为：真实模式 (非镜像)");
              }
            }
          } finally {
            if (winInfo != null) {
              try {
                winInfo.delete();
              } catch (Throwable ignored) {
              }
            }
          }
        }
      } catch (Throwable ignored) {
      } finally {
        if (win != null) {
          try {
            win.delete();
          } catch (Throwable ignored) {
          }
        }
      }
    }
    events.stateChanged();
  }

  synchronized void startPreview() throws Exception {
    startPreviewInternal(false);
  }

  /** Start the camera preview during an active video call (local PiP). */
  synchronized void startCallPreview() throws Exception {
    startPreviewInternal(true);
  }

  private void startPreviewInternal(boolean allowDuringCall) throws Exception {
    if (!started || endpoint == null) {
      throw new IllegalStateException("请先启动引擎");
    }
    if (activeCall != null && !allowDuringCall) {
      throw new IllegalStateException("通话中不能打开独立摄像头预览，请使用通话窗口里的摄像头开关");
    }
    if (!cameraEnabled) {
      throw new IllegalStateException("本地摄像头已被禁用，请先启用摄像头");
    }
    ensureThreadRegistered();
    if (videoPreview != null) {
      return;
    }
    VideoDetails vd = queryVideoDetails();
    if (!vd.supported || vd.captureDevId < 0) {
      throw new IllegalStateException("未检测到可用的视频输入设备");
    }
    videoPreview = new VideoPreview(vd.captureDevId);
    VideoPreviewOpParam opParam = new VideoPreviewOpParam();
    videoPreview.start(opParam);
    opParam.delete();
    VideoWindow win = null;
    try {
      win = videoPreview.getVideoWindow();
      if (win != null) {
        VideoWindowInfo winInfo = null;
        boolean nativeWindow = false;
        try {
          winInfo = win.getInfo();
          nativeWindow = winInfo != null && winInfo.getIsNative();
        } catch (Throwable t) {
          events.log("警告", "预览窗口尚未就绪: " + t.getMessage());
        }
        if (!nativeWindow) {
          MediaSize sz = new MediaSize();
          sz.setW(480);
          sz.setH(320);
          try {
            win.setSize(sz);
          } catch (Throwable t) {
            events.log("警告", "设置预览窗口分辨率失败: " + t.getMessage());
          } finally {
            sz.delete();
          }
          try {
            win.Show(true);
          } catch (Throwable t) {
            events.log("警告", "显示预览窗口失败: " + t.getMessage());
          }
        }

        // 方案 A：针对本地摄像头预览应用渲染层水平镜像变换（符合照镜子直觉，远端发送流不受影响）
        if (previewMirrorEnabled && winInfo != null && winInfo.getWinHandle() != null
            && winInfo.getWinHandle().getHandle() != null) {
          try {
            long hwnd = winInfo.getWinHandle().getHandle().getWindow();
            NativeMirror.applyPreviewMirror(hwnd, "摄像头预览 (镜像)");
          } catch (Throwable t) {
            events.log("系统", "提示: 原生图层镜像应用: " + t.getMessage());
          }
        }
        if (winInfo != null) {
          try {
            winInfo.delete();
          } catch (Throwable ignored) {
          }
        }
      }
    } catch (Exception ignored) {
    } finally {
      if (win != null) {
        try {
          win.delete();
        } catch (Throwable ignored) {
        }
      }
    }
    events.log("系统", "本地摄像头预览已开启 (" + vd.captureDev + (previewMirrorEnabled ? " · 镜像模式" : " · 真实模式") + ")");
    events.stateChanged();
  }

  synchronized void stopPreview() {
    if (videoPreview != null) {
      ensureThreadRegistered();
      VideoPreview vp = videoPreview;
      videoPreview = null;
      VideoWindow win = null;
      try {
        win = vp.getVideoWindow();
        if (win != null) {
          // SDL's synchronous Show/stop can wait for the JavaFX parent message pump.
          // Unparent before calling either while the renderer is still valid.
          WindowLayout.detach(0, nativeVideoWindowHandle(win));
          win.Show(false);
        }
      } catch (Throwable ignored) {
      } finally {
        if (win != null) {
          try {
            win.delete();
          } catch (Throwable ignored) {
          }
        }
      }
      try {
        vp.stop();
      } catch (Exception ignored) {
      }
      try {
        vp.delete();
      } catch (Exception ignored) {
      }
      events.log("系统", "本地摄像头预览已关闭");
      events.stateChanged();
    }
  }

  boolean hasIncoming() {
    ClientCall call;
    boolean ringing;
    synchronized (this) {
      call = activeCall;
      ringing = incomingRinging;
    }
    if (call == null || call.isOutgoing) {
      return false;
    }
    try {
      int state = call.getInfo().getState();
      return state == pjsip_inv_state.PJSIP_INV_STATE_INCOMING
          || state == pjsip_inv_state.PJSIP_INV_STATE_EARLY;
    } catch (Exception e) {
      return ringing;
    }
  }

  /** 当前来电是否带视频 offer。只在真正的 incoming call 上有效。 */
  synchronized boolean isIncomingVideo() {
    ClientCall call = activeCall;
    if (call == null || call.isOutgoing) {
      return false;
    }
    return call.offersVideo();
  }

  private volatile String mockTarget = null;
  private volatile Boolean mockVideo = null;
  private volatile Boolean mockVideoIntent = null;
  private volatile VideoWindow testNativeVideoWindow = null;

  synchronized void setMockCallStateForTest(String target, boolean isVideo) {
    setMockCallStateForTest(target, isVideo, isVideo);
  }

  synchronized void setMockCallStateForTest(String target, boolean isVideo, boolean isVideoIntent) {
    this.mockTarget = target;
    this.mockVideo = isVideo;
    this.mockVideoIntent = isVideoIntent;
    events.stateChanged();
  }

  synchronized void clearMockCallStateForTest() {
    this.mockTarget = null;
    this.mockVideo = null;
    this.mockVideoIntent = null;
    events.stateChanged();
  }

  synchronized void attachActiveVideoWindowForTest(VideoWindow win) {
    this.testNativeVideoWindow = win;
    if (activeCall != null) {
      activeCall.attachVideoWindow(win);
    }
  }

  synchronized VideoWindow getActiveVideoWindowForTest() {
    if (activeCall != null) {
      return activeCall.getActiveVideoWindow();
    }
    return testNativeVideoWindow;
  }

  synchronized boolean isInCall() {
    return activeCall != null || mockTarget != null;
  }

  synchronized String currentCallTarget() {
    if (mockTarget != null) {
      return mockTarget;
    }
    if (activeCall == null) {
      return "";
    }
    String dst = activeCall.dialedDst;
    if (dst != null && !dst.isBlank()) {
      return dst.trim();
    }
    try {
      CallInfo info = activeCall.getInfo();
      String remote = info.getRemoteUri();
      return remote != null ? remote.trim() : "";
    } catch (Exception e) {
      return dst != null ? dst.trim() : "";
    }
  }

  synchronized boolean isCurrentRoom(String roomSipUri) {
    if (mockTarget != null) {
      if (roomSipUri == null || roomSipUri.isBlank()) {
        return false;
      }
      String u1 = extractUser(mockTarget);
      String u2 = extractUser(roomSipUri);
      return !u1.isEmpty() && u1.equalsIgnoreCase(u2);
    }
    if (activeCall == null || roomSipUri == null || roomSipUri.isBlank()) {
      return false;
    }
    String cur = currentCallTarget();
    if (cur.isEmpty()) {
      return false;
    }
    String u1 = extractUser(cur);
    String u2 = extractUser(roomSipUri);
    return !u1.isEmpty() && u1.equalsIgnoreCase(u2);
  }

  synchronized boolean isInRoomCall() {
    if (mockTarget != null) {
      String u = extractUser(mockTarget);
      return u.startsWith("room-");
    }
    if (activeCall == null) {
      return false;
    }
    String cur = currentCallTarget();
    String u = extractUser(cur);
    return u.startsWith("room-");
  }

  synchronized boolean isCurrentCallVideo() {
    if (mockVideo != null) {
      return mockVideo;
    }
    if (testNativeVideoWindow != null) {
      return true;
    }
    if (activeCall == null) {
      return false;
    }
    return activeCall.isVideoActive();
  }

  /** True if the call was offered/started as a video call, even if it was answered audio-only. */
  synchronized boolean isCurrentCallVideoIntent() {
    if (mockVideoIntent != null) {
      return mockVideoIntent;
    }
    if (mockVideo != null) {
      return mockVideo;
    }
    return activeCall != null && activeCall.hasVideoIntent();
  }

  synchronized void closeActiveVideoWindow() {
    ensureThreadRegistered();
    if (testNativeVideoWindow != null) {
      try {
        testNativeVideoWindow.Show(false);
      } catch (Throwable ignored) {
      }
      try {
        testNativeVideoWindow.delete();
      } catch (Throwable ignored) {
      }
      testNativeVideoWindow = null;
    }
    if (activeCall != null) {
      activeCall.closeVideoWindow();
    }
  }

  String statusText() {
    ClientCall call;
    boolean on;
    boolean ringing;
    String proxy;
    int reg;
    synchronized (this) {
      on = started;
      call = activeCall;
      ringing = incomingRinging;
      proxy = outboundProxy;
      reg = lastRegCode;
    }
    if (mockTarget != null) {
      String remote = mockTarget;
      boolean isRoom = remote.contains("room-");
      if (isRoom) {
        boolean hasVideo = isCurrentCallVideo();
        String mode = hasVideo ? "视频会议 (3×3 混流)" : "语音会议 (纯音频)";
        return "在会中 · " + mode + "  " + remote;
      }
      return "通话中  " + remote;
    }
    if (!on) {
      return "引擎未启动";
    }
    if (call != null) {
      try {
        CallInfo info = call.getInfo();
        String remote = info.getRemoteUri();
        if (remote == null || remote.isBlank()) {
          remote = "";
        }
        boolean isRoom =
            (remote.contains("room-")
                || (call.dialedDst != null && call.dialedDst.contains("room-")));
        if (isRoom) {
          boolean hasVideo = false;
          try {
            for (int i = 0; i < info.getMedia().size(); i++) {
              if (info.getMedia().get(i).getType() == pjmedia_type.PJMEDIA_TYPE_VIDEO
                  && info.getMedia().get(i).getStatus()
                      == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                hasVideo = true;
                break;
              }
            }
          } catch (Exception ignored) {
          }
          String stateStr =
              (info.getState() == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED)
                  ? "在会中"
                  : info.getStateText();
          String mode = hasVideo ? "视频会议 (3×3 混流)" : "语音会议 (纯音频)";
          return stateStr + " · " + mode + (remote.isEmpty() ? "" : "  " + remote);
        }
        return info.getStateText() + (remote.isEmpty() ? "" : "  " + remote);
      } catch (Exception e) {
        return ringing ? "来电" : "通话中";
      }
    }
    if (reg == pjsip_status_code.PJSIP_SC_OK) {
      return "已注册" + (proxy.isEmpty() ? "" : "  代理 " + proxy);
    }
    return "已启动，未注册";
  }

  synchronized int boundPort() {
    return boundPort;
  }

  synchronized int start(int preferredPort, String publicIp) throws Exception {
    if (started) {
      return boundPort;
    }
    NativeLoader.load();
    int port = preferredPort <= 0 ? 5062 : preferredPort;
    if (port == 5060 && CampusLan.probeTcp("127.0.0.1")) {
      events.log("系统", "本机 5060 端口已有 SIP 代理服务 (Kamailio)，客户端自动切换至 5062 端口以避免冲突");
      port = 5062;
    }
    Exception last = null;
    for (int i = 0; i < 6; i++) {
      int tryPort = port + i * 2;
      try {
        openEndpoint(tryPort, publicIp);
        boundPort = tryPort;
        advertisedIp = publicIp == null ? "" : publicIp.trim();
        started = true;
        last = null;
        break;
      } catch (Exception e) {
        last = e;
        closeEndpointQuiet();
        events.log("系统", "本机 SIP 端口 " + tryPort + " 不可用，尝试 " + (tryPort + 2) + "：" + e.getMessage());
      }
    }
    if (!started) {
      throw last != null ? last : new IllegalStateException("无法绑定 SIP 端口");
    }
    String contact =
        publicIp == null || publicIp.isBlank() ? "" : "，Contact " + publicIp;
    events.log("系统", "引擎已启动，本机 SIP UDP/TCP " + boundPort + contact);
    VideoDetails vd = queryVideoDetails();
    if (vd.supported) {
      events.log(
          "系统",
          "视频引擎就绪：编解码器 "
              + vd.codec
              + "，输入设备 "
              + vd.captureDev
              + "，渲染器 "
              + vd.renderDev);
    } else {
      events.log("系统", "底层库运行于阶段 1 纯音频模式（未检测到视频支持）");
    }
    events.stateChanged();
    return boundPort;
  }

  void register(String server, String user, String password, String domain, String transport)
      throws Exception {
    if (!isStarted()) {
      throw new IllegalStateException("先启动引擎");
    }
    CampusLan.Target target = CampusLan.parseServer(server);
    if (target.host.isEmpty()) {
      throw new IllegalStateException("SIP 服务器为空");
    }
    boolean tcp = !"udp".equalsIgnoreCase(transport);
    String hostport = target.host + ":" + target.port;
    String sipTarget = "sip:" + hostport + (tcp ? ";transport=tcp" : ";transport=udp");
    AccountConfig cfg = new AccountConfig();
    cfg.setIdUri("sip:" + user + "@" + domain);
    // RURI 必须是 sip:<user>@lab；SIP 服务器只当 outbound proxy，否则域变成 127.0.0.1 会被 Kamailio 404。
    cfg.getRegConfig().setRegistrarUri("sip:" + user + "@" + domain);
    // REGISTER / INVITE 都带这条 Route。对话内 BYE/ACK 跟 Record-Route；Kamailio 双边 RR 会让本机仍走此代理。
    cfg.getRegConfig().setProxyUse(3);
    cfg.getNatConfig().setContactRewriteUse(0);
    cfg.getNatConfig().setViaRewriteUse(0);
    cfg.getNatConfig().setContactUseSrcPort(0);
    if (hasVideoSupport()) {
      VideoMediaPolicy.configureAccount(cfg);
      cfg.getVideoConfig().setAutoShowIncoming(false);
      cfg.getVideoConfig().setAutoTransmitOutgoing(true);
      int capDev = findDefaultCaptureDev();
      if (capDev >= 0) {
        cfg.getVideoConfig().setDefaultCaptureDevice(capDev);
      }
    } else {
      cfg.getVideoConfig().setAutoShowIncoming(false);
      cfg.getVideoConfig().setAutoTransmitOutgoing(false);
    }
    // 经 127.0.0.1 连 Docker 时 PJSIP 会把 Contact 写成回环，容器内 INVITE 回不了本机。
    String advertise = advertisedIp.isBlank() ? CampusLan.detect() : advertisedIp;
    if (!advertise.isBlank() && !advertise.equals("127.0.0.1") && !advertise.equals("localhost")) {
      String contact = "sip:" + user + "@" + advertise + ":" + boundPort;
      if (tcp) {
        contact += ";transport=tcp";
      }
      cfg.getSipConfig().setContactForced(contact);
      events.log("系统", "Contact " + contact);
    }
    if (tcp) {
      cfg.getSipConfig().setContactUriParams(";transport=tcp");
    }
    StringVector proxies = cfg.getSipConfig().getProxies();
    proxies.add(sipTarget);
    cfg.getSipConfig().setProxies(proxies);
    if (password != null && !password.isEmpty()) {
      cfg.getSipConfig().getAuthCreds().add(new AuthCredInfo("digest", "*", user, 0, password));
    }

    ClientAccount current;
    synchronized (this) {
      current = account;
    }

    // 若当前账号对象有效且用户一致，使用 modify 原地更新并调用 setRegistration(true) 触发重注册
    if (current != null && current.isValid() && user.equals(current.getUsername())) {
      try {
        current.modify(cfg);
        synchronized (this) {
          outboundProxy = sipTarget;
        }
        current.setRegistration(true);
        events.log("注册", "正在重新发起 REGISTER " + cfg.getIdUri() + " → " + sipTarget);
        events.stateChanged();
        return;
      } catch (Exception ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        if (msg.contains("EBUSY") || msg.toLowerCase().contains("busy")) {
          events.log("注册", "注册请求已在处理中，无需重复发起");
          events.stateChanged();
          return;
        }
        events.log("系统", "复用账号配置更新失败 (" + msg + ")，将尝试重建账号");
      }
    }

    if (current != null) {
      try {
        current.delete();
      } catch (Exception ignored) {
      }
      synchronized (this) {
        if (account == current) {
          account = null;
        }
      }
    }

    ClientAccount next = new ClientAccount(user);
    synchronized (this) {
      clearBuddiesLocked();
      account = next;
      lastRegCode = 0;
      outboundProxy = sipTarget;
    }
    try {
      next.create(cfg);
      events.log("注册", "正在 REGISTER " + cfg.getIdUri() + " → " + sipTarget);
    } catch (Exception ex) {
      events.log("警告", "发起注册异常：" + ex.getMessage() + "，若显示 busy 请稍候重试");
      throw ex;
    }
    events.stateChanged();
  }

  void call(String peerUser, String domain) throws Exception {
    call(peerUser, domain, true);
  }

  void call(String peerUser, String domain, boolean video) throws Exception {
    if (!isRegistered()) {
      throw new IllegalStateException("尚未注册成功");
    }
    if (video && !hasVideoSupport()) {
      throw new IllegalStateException("当前底层库未启用视频支持（阶段 1 纯音频库）");
    }
    if (isPreviewActive()) {
      events.log("系统", "正在发起呼叫，自动停止本地摄像头预览以释放设备");
      stopPreview();
    }
    hangupQuiet();
    String dst = peerUser.trim();
    if (!dst.startsWith("sip:")) {
      dst = "sip:" + dst + "@" + domain;
    }
    ClientAccount acc;
    ClientCall next;
    String proxy;
    synchronized (this) {
      if (account == null || lastRegCode != pjsip_status_code.PJSIP_SC_OK) {
        throw new IllegalStateException("尚未注册成功");
      }
      acc = account;
      next = new ClientCall(acc, dst, video);
      activeCall = next;
      lastCall = next;
      proxy = outboundProxy;
    }
    CallOpParam prm = new CallOpParam(true);
    prm.getOpt().setTextCount(0);
    if (!video) {
      prm.getOpt().setVideoCount(0);
    }
    next.makeCall(dst, prm);
    boolean isRoom = dst.contains("room-");
    if (isRoom) {
      if (video) {
        events.log(
            "通话",
            "正在发起【视频参会】接入 MCU 会议室 "
                + dst
                + " (拉起 3×3 混流画布)"
                + (proxy.isEmpty() ? "" : " 经 " + proxy));
      } else {
        events.log(
            "通话",
            "正在发起【语音参会】接入 MCU 会议室 "
                + dst
                + " (纯音频通话，无视频窗)"
                + (proxy.isEmpty() ? "" : " 经 " + proxy));
      }
    } else {
      events.log(
          "通话",
          "正在发起" + (video ? "视频" : "语音") + "呼叫 " + dst + (proxy.isEmpty() ? "" : " 经 " + proxy));
    }
    events.stateChanged();
  }

  void hangup() {
    ensureThreadRegistered();
    ClientCall call;
    String proxy;
    String mock;
    synchronized (this) {
      call = activeCall;
      proxy = outboundProxy;
      mock = mockTarget;
    }
    if (call == null) {
      if (mock != null) {
        clearMockCallStateForTest();
        closeActiveVideoWindow();
        events.log("通话", "已退出会议/挂断");
        events.stateChanged();
        return;
      }
      events.log("通话", "当前没有通话");
      events.stateChanged();
      return;
    }
    closeActiveVideoWindow();
    events.log("通话", "正在发送 BYE" + (proxy.isEmpty() ? "" : " 经 " + proxy));
    armHangupWatch(call);
    hangupQuiet(false);
    events.stateChanged();
  }

  void answer() throws Exception {
    answer(true);
  }

  void answer(boolean video) throws Exception {
    ClientCall call;
    synchronized (this) {
      call = activeCall;
    }
    if (call == null) {
      throw new IllegalStateException("没有来电");
    }
    if (video && !hasVideoSupport()) {
      throw new IllegalStateException("当前底层库未启用视频支持（阶段 1 纯音频库）");
    }
    if (isPreviewActive()) {
      events.log("系统", "正在接听呼叫，自动停止本地摄像头预览以释放设备");
      stopPreview();
    }
    call.setVideoConfigured(video);
    CallInfo info = call.getInfo();
    int state = info.getState();
    if (state != pjsip_inv_state.PJSIP_INV_STATE_INCOMING
        && state != pjsip_inv_state.PJSIP_INV_STATE_EARLY) {
      throw new IllegalStateException("当前不是来电：" + info.getStateText());
    }
    CallOpParam prm = new CallOpParam(true);
    prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
    prm.getOpt().setTextCount(0);
    if (!video) {
      prm.getOpt().setVideoCount(0);
    }
    call.answer(prm);
    synchronized (this) {
      if (activeCall == call) {
        incomingRinging = false;
      }
    }
    events.log("通话", "已接听" + (video ? "（视频）" : "（语音）") + " " + nullToEmpty(info.getRemoteUri()));
    events.stateChanged();
  }

  void shutdown() {
    ensureThreadRegistered();
    stopPreview();
    cancelHangupWatch();
    ClientCall last = hangupQuiet();
    // 必须显式 delete 通话对象再销毁 endpoint。否则残留下来的 Call 会在 JVM 退出时
    // 由 finalize 回调 pjsua_call_set_user_data，而此时 max_calls 已归零，
    // pjsua_call.c 的断言会 abort（退出码 3）。
    ClientCall pending;
    synchronized (this) {
      pending = activeCall;
      activeCall = null;
    }
    deleteCallQuietly(last);
    if (pending != null && pending != last) {
      deleteCallQuietly(pending);
    }
    ClientCall stray;
    synchronized (this) {
      stray = lastCall;
      lastCall = null;
    }
    if (stray != null && stray != last && stray != pending) {
      deleteCallQuietly(stray);
    }
    ClientAccount acc;
    synchronized (this) {
      clearBuddiesLocked();
      acc = account;
      account = null;
    }
    if (acc != null) {
      try {
        acc.shutdown();
      } catch (Exception ignored) {
      }
      try {
        acc.delete();
      } catch (Exception ignored) {
      }
    }
    // libDestroy joins SIP workers. Their final BYE/log callbacks can acquire this
    // monitor, so holding it here deadlocks shutdown against those workers.
    closeEndpointQuiet();
    synchronized (this) {
      started = false;
      lastRegCode = 0;
      incomingRinging = false;
      advertisedIp = "";
      outboundProxy = "";
    }
    hangupWatch.shutdownNow();
    events.stateChanged();
  }

  synchronized void sendMessage(String peerUser, String domain, String content) throws Exception {
    if (!isRegistered()) {
      throw new IllegalStateException("尚未注册成功，无法发送消息");
    }
    if (peerUser == null || peerUser.trim().isEmpty()) {
      throw new IllegalArgumentException("接收方不能为空");
    }
    if (content == null || content.isEmpty()) {
      throw new IllegalArgumentException("消息内容不能为空");
    }
    String p = peerUser.trim();
    String targetUri;
    if (p.startsWith("sip:")) {
      targetUri = p;
    } else if (p.contains("@")) {
      targetUri = "sip:" + p;
    } else {
      String dom = (domain == null || domain.trim().isEmpty()) ? "lab" : domain.trim();
      targetUri = "sip:" + p + "@" + dom;
    }

    Buddy buddy = buddies.get(targetUri);
    if (buddy == null || !buddy.isValid()) {
      if (buddy != null) {
        try {
          buddy.delete();
        } catch (Exception ignored) {
        }
      }
      BuddyConfig bCfg = new BuddyConfig();
      bCfg.setUri(targetUri);
      bCfg.setSubscribe(false);
      buddy = new Buddy();
      buddy.create(account, bCfg);
      bCfg.delete();
      buddies.put(targetUri, buddy);
    }

    SendInstantMessageParam prm = new SendInstantMessageParam();
    prm.setContent(content);
    prm.setContentType("text/plain");
    try {
      buddy.sendInstantMessage(prm);
    } finally {
      prm.delete();
    }
    String logContent = content.startsWith("[FILE]") ? "[文件通知]" : content;
    events.log("消息", "向 " + targetUri + " 发送：" + logContent);
  }

  private void clearBuddiesLocked() {
    for (Buddy b : buddies.values()) {
      try {
        b.delete();
      } catch (Exception ignored) {
      }
    }
    buddies.clear();
  }

  private void openEndpoint(int port, String publicIp) throws Exception {
    endpoint = new Endpoint();
    endpoint.libCreate();
    EpConfig epConfig = new EpConfig();
    try {
      logWriter = new ClientLogWriter();
      epConfig.getLogConfig().setWriter(logWriter);
      // pjsua 只把 level <= consoleLevel 的日志交给自定义 writer；SIP 报文 dump 是 4 级。
      epConfig.getLogConfig().setLevel(4);
      epConfig.getLogConfig().setConsoleLevel(4);
      epConfig.getLogConfig().setMsgLogging(1);
      long decor = epConfig.getLogConfig().getDecor();
      decor &=
          ~(pj_log_decoration.PJ_LOG_HAS_CR
              | pj_log_decoration.PJ_LOG_HAS_NEWLINE
              | pj_log_decoration.PJ_LOG_HAS_COLOR);
      epConfig.getLogConfig().setDecor(decor);
      // 显式禁用底层 Native Preview，强制使用标准渲染流水线 (Metal/DirectShow)，根治 vid_port.c:728 空指针断言 (exit 134)
      epConfig.getMedConfig().setVidPreviewEnableNative(false);
      endpoint.libInit(epConfig);
    } finally {
      epConfig.delete();
    }
    try {
      endpoint.libRegisterThread(Thread.currentThread().getName());
    } catch (Throwable ignored) {
    }
    try {
      Platform.runLater(() -> {
        try {
          if (endpoint != null && !endpoint.libIsThreadRegistered()) {
            endpoint.libRegisterThread("javafx-ui");
          }
        } catch (Throwable ignored) {
        }
      });
    } catch (Throwable ignored) {
    }
    createTransport(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, port, publicIp);
    createTransport(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, port, publicIp);
    endpoint.libStart();
    try {
      VideoMediaPolicy.configureCodecs(endpoint, message -> events.log("视频", message));
    } catch (Exception e) {
      events.log("警告", "视频编码参数设置失败，保留原生默认值：" + e.getMessage());
    }
  }

  private void createTransport(int type, int port, String publicIp) throws Exception {
    TransportConfig transport = new TransportConfig();
    try {
      transport.setPort(port);
      if (publicIp != null && !publicIp.isBlank()) {
        transport.setPublicAddress(publicIp);
      }
      endpoint.transportCreate(type, transport);
    } finally {
      transport.delete();
    }
  }

  private void closeEndpointQuiet() {
    if (endpoint != null) {
      ensureThreadRegistered();
      try {
        endpoint.libDestroy();
      } catch (Exception ignored) {
      }
      try {
        endpoint.delete();
      } catch (Exception ignored) {
      }
      endpoint = null;
    }
    logWriter = null;
  }

  private ClientCall hangupQuiet() {
    return hangupQuiet(true);
  }

  private ClientCall hangupQuiet(boolean cancelWatch) {
    ensureThreadRegistered();
    if (cancelWatch) {
      cancelHangupWatch();
    }
    ClientCall call;
    synchronized (this) {
      call = activeCall;
      incomingRinging = false;
      if (call != null) {
        call.localHangup = true;
      }
    }
    if (call == null) {
      return null;
    }
    call.closeVideoWindow();
    try {
      call.hangup(new CallOpParam());
    } catch (Exception ignored) {
      // already gone; DISCONNECTED will delete
    }
    return call;
  }

  private static void deleteCallQuietly(Call call) {
    if (call == null) {
      return;
    }
    try {
      call.delete();
    } catch (Throwable ignored) {
      // 已经删过了
    }
  }

  // PJSIP 一发出 BYE 就把通话置为 DISCONNECTED，不等应答；要判断对端是否收到，只能看 BYE 的响应。
  private void armHangupWatch(ClientCall call) {
    cancelHangupWatch();
    final int seq;
    synchronized (this) {
      seq = ++hangupWatchSeq;
      byeAnswered = false;
    }
    hangupWatchTask =
        hangupWatch.schedule(
            () -> {
              synchronized (SipService.this) {
                if (seq != hangupWatchSeq || byeAnswered) {
                  return;
                }
              }
              events.log(
                  "警告",
                  "BYE 发出 4 秒没有收到应答，对端可能仍显示通话中。"
                      + "本机 Docker 的 SIP 服务器要填 127.0.0.1，Mac 填校园网 IP；信令侧看 Kamailio 日志。");
            },
            4,
            TimeUnit.SECONDS);
  }

  private void onByeResponse(int code, String reason) {
    boolean first;
    synchronized (this) {
      first = !byeAnswered;
      byeAnswered = true;
    }
    if (!first) {
      return;
    }
    cancelHangupWatch();
    if (code >= 200 && code < 300) {
      events.log("通话", "对端已确认挂断 " + code + " " + reason);
    } else if (code >= 300) {
      events.log("警告", "BYE 被拒绝 " + code + " " + reason + "，对端可能仍显示通话中");
    }
  }

  private void cancelHangupWatch() {
    ScheduledFuture<?> task;
    synchronized (this) {
      hangupWatchSeq++;
      task = hangupWatchTask;
      hangupWatchTask = null;
    }
    if (task != null) {
      task.cancel(false);
    }
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private final class ClientLogWriter extends LogWriter {
    private java.io.PrintWriter fileSink;
    private boolean fileSinkInit;

    private void mirrorToFile(String msg) {
      if (!fileSinkInit) {
        fileSinkInit = true;
        String path = System.getenv("UI_LOG_FILE");
        if (path != null && !path.isBlank()) {
          try {
            fileSink = new java.io.PrintWriter(new java.io.FileWriter(path, true), true);
          } catch (Throwable ignored) {
            fileSink = null;
          }
        }
      }
      if (fileSink != null) {
        fileSink.println(msg);
      }
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
        // never throw into PJSUA2
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
          if (code >= 200) {
            onByeResponse(code, parts.length > 2 ? parts[2] : "");
          }
        } catch (NumberFormatException ignored) {
          // not a status line
        }
      }
      return true;
    }

    private String firstSipLine(String msg) {
      String[] lines = msg.split("\\r?\\n");
      for (String line : lines) {
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

    private String compactPjsipHeader(String msg) {
      int nl = msg.indexOf('\n');
      String head = nl < 0 ? msg : msg.substring(0, nl);
      return head.replaceAll("\\s+", " ").trim();
    }
  }

  private final class ClientAccount extends Account {
    private final String username;

    ClientAccount(String username) {
      this.username = username != null ? username : "";
    }

    String getUsername() {
      return username;
    }

    @Override
    public void onRegState(OnRegStateParam prm) {
      ensureThreadRegistered();
      synchronized (SipService.this) {
        if (account != this) {
          return;
        }
        lastRegCode = prm.getCode();
      }
      String cat = prm.getCode() == pjsip_status_code.PJSIP_SC_OK ? "注册" : "警告";
      events.log(cat, "注册：" + prm.getCode() + " " + prm.getReason());
      events.stateChanged();
    }

    @Override
    public void onIncomingCall(OnIncomingCallParam prm) {
      ensureThreadRegistered();
      ClientCall incoming;
      synchronized (SipService.this) {
        if (activeCall != null) {
          // 当前已有正在进行的通话（或振铃），向第二路新来电直接回复 486 Busy Here
          ClientCall busyCall = new ClientCall(this, prm.getCallId());
          try {
            CallOpParam busyParam = new CallOpParam();
            busyParam.setStatusCode(pjsip_status_code.PJSIP_SC_BUSY_HERE);
            busyCall.hangup(busyParam);
          } catch (Exception e) {
            events.log("警告", "回 486 Busy 失败：" + e.getMessage());
          }
          try {
            Platform.runLater(
                () -> {
                  try {
                    ensureThreadRegistered();
                    busyCall.delete();
                  } catch (Exception ignored) {
                  }
                });
          } catch (IllegalStateException e) {
            // Toolkit not initialized (e.g. headless unit tests)
            try {
              ensureThreadRegistered();
              busyCall.delete();
            } catch (Exception ignored) {
            }
          }
          events.log("通话", "当前已有正在进行的通话，已向新来电回复 486 Busy Here (callId=" + prm.getCallId() + ")");
          return;
        }
        incoming = new ClientCall(this, prm.getCallId());
        activeCall = incoming;
        lastCall = incoming;
        incomingRinging = true;
        incoming.setVideoIntent(incoming.offersVideo());
      }
      String remote = "";
      String callId = "";
      try {
        CallInfo info = incoming.getInfo();
        remote = nullToEmpty(info.getRemoteUri());
        callId = nullToEmpty(info.getCallIdString());
        CallOpParam ring = new CallOpParam();
        ring.setStatusCode(pjsip_status_code.PJSIP_SC_RINGING);
        incoming.answer(ring);
      } catch (Exception e) {
        events.log("警告", "回 180 失败：" + e.getMessage());
      }
      events.log(
          "通话",
          "来电 "
              + remote
              + " callId="
              + prm.getCallId()
              + (callId.isEmpty() ? "" : " " + callId)
              + "，点接听");
      events.stateChanged();
    }

    @Override
    public void onInstantMessage(OnInstantMessageParam prm) {
      String fromUri = nullToEmpty(prm.getFromUri());
      String toUri = nullToEmpty(prm.getToUri());
      String body = nullToEmpty(prm.getMsgBody());
      String contentType = nullToEmpty(prm.getContentType());
      if (isVideoAvatarControl(body)) {
        events.videoAvatarChanged(fromUri, VIDEO_AVATAR_OFF.equals(body));
        return;
      }
      String logBody = body.startsWith("[FILE]") ? "[文件通知]" : body;
      events.log("消息", "收到来自 " + fromUri + " 的消息：" + logBody);
      events.instantMessageReceived(fromUri, toUri, body, contentType);
    }

    @Override
    public void onInstantMessageStatus(OnInstantMessageStatusParam prm) {
      String toUri = nullToEmpty(prm.getToUri());
      String body = nullToEmpty(prm.getMsgBody());
      int code = prm.getCode();
      String reason = nullToEmpty(prm.getReason());
      String cat = code == pjsip_status_code.PJSIP_SC_OK ? "消息" : "警告";
      String desc = code == pjsip_status_code.PJSIP_SC_OK ? "送达成功" : ("发送未送达(" + code + " " + reason + ")");
      events.log(cat, "向 " + toUri + " 消息 " + desc);
      events.instantMessageStatus(toUri, body, code, reason);
    }
  }

  private final class ClientCall extends Call {
    private final java.time.Instant initiateTime = java.time.Instant.now();
    private volatile java.time.Instant connectTime;
    private final boolean isOutgoing;
    private final String dialedDst;
    private volatile boolean videoConfigured;
    private volatile boolean videoStreamActive;
    private volatile VideoWindow activeVideoWindow;
    private volatile int activeVideoWinId = -1;
    private volatile boolean localHangup;
    private volatile boolean callDisconnected;
    private volatile boolean callEndedDispatched;
    private volatile int videoMediaIndex = -1;
    /** Whether the call was originally offered as video, even if answered voice-only. */
    private volatile boolean videoIntent;

    ClientCall(Account acc, String dialedDst, boolean videoConfigured) {
      super(acc);
      this.isOutgoing = true;
      this.dialedDst = dialedDst != null ? dialedDst : "";
      this.videoConfigured = videoConfigured;
      this.videoIntent = videoConfigured;
    }

    ClientCall(Account acc, String dialedDst) {
      this(acc, dialedDst, false);
    }

    ClientCall(Account acc, int callId) {
      super(acc, callId);
      this.isOutgoing = false;
      this.dialedDst = "";
      this.videoConfigured = false;
    }

    synchronized void setVideoConfigured(boolean video) {
      this.videoConfigured = video;
      if (video) {
        this.videoIntent = true;
      }
    }

    synchronized void setVideoIntent(boolean video) {
      this.videoIntent = video;
    }

    synchronized boolean hasVideoIntent() {
      return videoIntent;
    }

    synchronized boolean isVideoActive() {
      if (videoStreamActive || activeVideoWindow != null) {
        return true;
      }
      if (connectTime == null && videoConfigured) {
        return true;
      }
      try {
        CallInfo ci = getInfo();
        for (int i = 0; i < ci.getMedia().size(); i++) {
          CallMediaInfo media = ci.getMedia().get(i);
          if (media.getType() == pjmedia_type.PJMEDIA_TYPE_VIDEO
              && media.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
            return true;
          }
        }
      } catch (Throwable ignored) {
      }
      return false;
    }

    /** 来电 SDP 里是否包含视频媒体（不要求已经协商为 ACTIVE）。 */
    synchronized boolean offersVideo() {
      if (videoConfigured || videoStreamActive || activeVideoWindow != null) {
        return true;
      }
      try {
        CallInfo ci = getInfo();
        if (ci.getRemVideoCount() > 0) {
          return true;
        }
        // 振铃阶段 getMedia() 还没有 active 媒体，视频 offer 在 provMedia 里。
        if (containsVideo(ci.getMedia()) || containsVideo(ci.getProvMedia())) {
          return true;
        }
      } catch (Throwable ignored) {
      }
      return false;
    }

    private static boolean containsVideo(org.pjsip.pjsua2.CallMediaInfoVector media) {
      if (media == null) {
        return false;
      }
      for (int i = 0; i < media.size(); i++) {
        if (media.get(i).getType() == pjmedia_type.PJMEDIA_TYPE_VIDEO) {
          return true;
        }
      }
      return false;
    }

    synchronized void attachVideoWindow(VideoWindow win) {
      this.activeVideoWindow = win;
      this.videoStreamActive = (win != null);
    }

    synchronized VideoWindow getActiveVideoWindow() {
      return this.activeVideoWindow;
    }

    synchronized void closeVideoWindow() {
      ensureThreadRegistered();
      VideoWindow win = activeVideoWindow;
      activeVideoWindow = null;
      activeVideoWinId = -1;
      videoStreamActive = false;
      videoMediaIndex = -1;
      if (win == null) {
        return;
      }
      // The UI detaches cached HWNDs before closing. PJSIP owns the renderer and
      // may already be releasing it: even getInfo()/Show(false) can assert here.
      // Keep preview detachment in stopPreview(), where its window is still valid.
      abandonVideoWindow(win);
    }

    private static void abandonVideoWindow(VideoWindow win) {
      if (win == null) {
        return;
      }
      // 不再调用任何会查 wid / vp 的原生方法；把 SWIG 指针清零，
      // 让 delete() 和 finalize() 都变成空操作，避免进程退出时再次断言。
      try {
        java.lang.reflect.Field ptr = VideoWindow.class.getDeclaredField("swigCPtr");
        ptr.setAccessible(true);
        ptr.setLong(win, 0L);
        java.lang.reflect.Field own = VideoWindow.class.getDeclaredField("swigCMemOwn");
        own.setAccessible(true);
        own.setBoolean(win, false);
      } catch (Throwable ignored) {
      }
    }

    synchronized void setTransmitVideo(boolean transmit) {
      if (!videoStreamActive) return;
      try {
        ensureThreadRegistered();
        CallVidSetStreamParam vParam = new CallVidSetStreamParam();
        try {
          vParam.setMedIdx(videoMediaIndex >= 0 ? videoMediaIndex : -1);
          int op = transmit
              ? pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_START_TRANSMIT
              : pjsua_call_vid_strm_op.PJSUA_CALL_VID_STRM_STOP_TRANSMIT;
          if (transmit) {
            int devId = findDefaultCaptureDev();
            if (devId >= 0) {
              vParam.setCapDev(devId);
            }
          }
          vidSetStream(op, vParam);
          events.log("通话", "本地视频推流已" + (transmit ? "恢复传输" : "暂停传输"));
        } finally {
          vParam.delete();
        }
      } catch (Throwable t) {
        events.log("警告", "设置视频推流状态失败: " + t.getMessage());
      }
    }

    @Override
    public void onCallState(OnCallStateParam prm) {
      ensureThreadRegistered();
      try {
        CallInfo ci = getInfo();
        int state = ci.getState();
        if (state == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
          if (connectTime == null) {
            connectTime = java.time.Instant.now();
            events.log("通话", "通话已接通确认 (开始计时)");
          }
          // The outgoing side has no answer-button action to refresh its window.
          events.stateChanged();
        } else if (state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
          callDisconnected = true;
          boolean local = localHangup;
          synchronized (SipService.this) {
            if (activeCall == this) {
              activeCall = null;
              incomingRinging = false;
              callAudioMedia = null;
              captureAudioMedia = null;
              audioMuted = false;
            }
          }
          String detail = ci.getLastStatusCode() + " " + ci.getLastReason();
          if (local) {
            // 本端挂断：DISCONNECTED 只说明 BYE 已发出，是否送达看 onByeResponse。
            events.log("通话", "本端已断开 " + detail + "，等待对端应答 BYE");
          } else {
            cancelHangupWatch();
            events.log("通话", "对端已挂断 " + detail);
          }

          if (!callEndedDispatched) {
            callEndedDispatched = true;
            boolean connected = (connectTime != null);
            java.time.Instant startAt = connected ? connectTime : initiateTime;
            long durationSec =
                connected
                    ? Math.max(0, java.time.Duration.between(connectTime, java.time.Instant.now()).getSeconds())
                    : 0;

            String callerUser;
            String calleeUser;
            String myUser = "";
            synchronized (SipService.this) {
              if (account != null) {
                myUser = account.getUsername();
              }
            }
            if (isOutgoing) {
              callerUser = !myUser.isEmpty() ? myUser : extractUser(ci.getLocalUri());
              calleeUser = !dialedDst.isEmpty() ? extractUser(dialedDst) : extractUser(ci.getRemoteUri());
            } else {
              callerUser = extractUser(ci.getRemoteUri());
              calleeUser = !myUser.isEmpty() ? myUser : extractUser(ci.getLocalUri());
            }
            if (callerUser.isEmpty()) callerUser = "unknown";
            if (calleeUser.isEmpty()) calleeUser = "unknown";

            String callId = nullToEmpty(ci.getCallIdString());
            events.callEnded(callId, callerUser, calleeUser, startAt, durationSec, connected);
          }

          Platform.runLater(
              () -> {
                try {
                  ensureThreadRegistered();
                  closeVideoWindow();
                } catch (Throwable ignored) {
                }
                try {
                  ensureThreadRegistered();
                  delete();
                } catch (Exception ignored) {
                  // already gone
                }
              });
          events.stateChanged();
        } else {
          events.log("通话", "状态 " + ci.getStateText() + " " + nullToEmpty(ci.getRemoteUri()));
        }
      } catch (Exception e) {
        events.log("错误", "通话状态错误：" + e.getMessage());
      }
    }


    @Override
    public void onCallMediaState(OnCallMediaStateParam prm) {
      ensureThreadRegistered();
      try {
        CallInfo ci = getInfo();
        org.pjsip.pjsua2.CallMediaInfoVector mediaVec = ci.getMedia();
        for (int i = 0; i < mediaVec.size(); i++) {
          CallMediaInfo media = mediaVec.get(i);
          if (media.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO) {
            AudioMedia audio = getAudioMedia(i);
            AudioMedia capture = endpoint.audDevManager().getCaptureDevMedia();
            callAudioMedia = audio;
            captureAudioMedia = capture;
            if (!audioMuted) {
              capture.startTransmit(audio);
            }
            audio.startTransmit(endpoint.audDevManager().getPlaybackDevMedia());
            events.log("通话", "音频已接通" + (audioMuted ? "（静音中）" : ""));

          } else if (media.getType() == pjmedia_type.PJMEDIA_TYPE_VIDEO) {
            if (media.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
              synchronized (this) {
                this.videoStreamActive = true;
                this.videoMediaIndex = i;
              }
              boolean room = (dialedDst != null && dialedDst.contains("room-"))
                  || (ci.getRemoteUri() != null && ci.getRemoteUri().contains("room-"));
              // Sending must not depend on the remote renderer already having a window.
              // Otherwise members can wait on each other's first frame indefinitely.
              if (room && hasCaptureDevice() && isCameraEnabled()) {
                setTransmitVideo(true);
              }
              int winId = media.getVideoIncomingWindowId();
              if (winId >= 0) {
                try {
                  VideoWindow vidWin;
                  synchronized (this) {
                    if (this.activeVideoWinId == winId && this.activeVideoWindow != null) {
                      vidWin = this.activeVideoWindow;
                    } else {
                      vidWin = new VideoWindow(winId);
                      if (this.activeVideoWindow != null) {
                        try {
                          this.activeVideoWindow.Show(false);
                          this.activeVideoWindow.delete();
                        } catch (Throwable ignored) {
                        }
                      }
                      this.activeVideoWindow = vidWin;
                      this.activeVideoWinId = winId;
                    }
                    this.videoStreamActive = true;
                    this.videoMediaIndex = i;
                  }
                  VideoWindowInfo winInfo = null;
                  boolean nativeWindow = false;
                  try {
                    winInfo = vidWin.getInfo();
                    nativeWindow = winInfo != null && winInfo.getIsNative();
                  } catch (Throwable t) {
                    events.log("警告", "视频窗口尚未就绪: " + t.getMessage());
                  } finally {
                    if (winInfo != null) {
                      try {
                        winInfo.delete();
                      } catch (Throwable ignored) {
                      }
                    }
                  }
                  boolean isRoom = (dialedDst != null && dialedDst.contains("room-"))
                      || (ci.getRemoteUri() != null && ci.getRemoteUri().contains("room-"));
                  boolean sizeSet = false;
                  if (!nativeWindow) {
                    MediaSize sz = new MediaSize();
                    if (isRoom) {
                      sz.setW(960);
                      sz.setH(540);
                    } else {
                      sz.setW(640);
                      sz.setH(360);
                    }
                    try {
                      vidWin.setSize(sz);
                      sizeSet = true;
                    } catch (Throwable t) {
                      events.log("警告", "设置视频窗口分辨率失败: " + t.getMessage());
                    } finally {
                      sz.delete();
                    }
                    try {
                      vidWin.Show(true);
                    } catch (Throwable t) {
                      events.log("警告", "显示视频窗口失败: " + t.getMessage());
                    }
                    // 不使用 SDL 独占全屏：它会切换桌面分辨率/缩放，导致整个
                    // JavaFX 界面看起来被放大。保持普通独立窗口。
                  }
                  if (isRoom) {
                    boolean hasCap = hasCaptureDevice();
                    boolean camOn = isCameraEnabled();
                    String capNote;
                    if (!hasCap) {
                      capNote = "本地无可用摄像头，仅接收混流画面";
                    } else if (camOn) {
                      capNote = "已请求本地摄像头推流，发送结果见推流状态日志";
                    } else {
                      capNote = "本地摄像头已禁用，MCU 画布正以个人头像展示";
                    }
                    if (sizeSet) {
                      events.log(
                          "通话",
                          "MCU 会议室视频流已接通，正在渲染 3×3 九宫格混流画布 (窗口 ID="
                              + winId
                              + ", 分辨率=960×540，"
                              + capNote
                              + "。若会议室其他成员未开启视频推流，其余宫格显示为黑底)");
                    } else {
                      events.log(
                          "通话",
                          "MCU 会议室视频流已接通，使用系统默认分辨率 (窗口 ID="
                              + winId
                              + "，"
                              + capNote
                              + "。若无远端画面其余宫格显示为黑底)");
                    }
                  } else {
                    String resStr = sizeSet ? ", 分辨率=640×360" : "";
                    events.log("通话", "点对点双向视频流已接通，已拉起独立原生视频窗口 (窗口 ID=" + winId + resStr + ")");
                  }
                } catch (Exception ve) {
                  events.log("警告", "视频窗口显示异常: " + ve.getMessage());
                }
              } else {
                events.log("通话", "视频媒体已激活（等待远端画面）");
              }
            }
          }
        }
      } catch (Exception e) {
        events.log("错误", "媒体连接处理失败：" + e.getMessage());
      } finally {
        // Video may become ready after CONFIRMED; refresh the renderer/PiP then as well.
        events.stateChanged();
      }
    }

    @Override
    public void onInstantMessage(OnInstantMessageParam prm) {
      String fromUri = nullToEmpty(prm.getFromUri());
      String toUri = nullToEmpty(prm.getToUri());
      String body = nullToEmpty(prm.getMsgBody());
      String contentType = nullToEmpty(prm.getContentType());
      if (isVideoAvatarControl(body)) {
        events.videoAvatarChanged(fromUri, VIDEO_AVATAR_OFF.equals(body));
        return;
      }
      String logBody = body.startsWith("[FILE]") ? "[文件通知]" : body;
      events.log("消息", "收到通话内来自 " + fromUri + " 的消息：" + logBody);
      events.instantMessageReceived(fromUri, toUri, body, contentType);
    }

    @Override
    public void onInstantMessageStatus(OnInstantMessageStatusParam prm) {
      String toUri = nullToEmpty(prm.getToUri());
      String body = nullToEmpty(prm.getMsgBody());
      int code = prm.getCode();
      String reason = nullToEmpty(prm.getReason());
      String cat = code == pjsip_status_code.PJSIP_SC_OK ? "消息" : "警告";
      String desc = code == pjsip_status_code.PJSIP_SC_OK ? "送达成功" : ("发送未送达(" + code + " " + reason + ")");
      events.log(cat, "向 " + toUri + " 消息 " + desc);
      events.instantMessageStatus(toUri, body, code, reason);
    }
  }

  static String extractUser(String uri) {
    if (uri == null || uri.isBlank()) {
      return "";
    }
    String s = uri.trim();
    int sipIdx = s.indexOf("sip:");
    if (sipIdx != -1) {
      s = s.substring(sipIdx + 4);
    }
    int atIdx = s.indexOf('@');
    if (atIdx != -1) {
      s = s.substring(0, atIdx);
    }
    int semiIdx = s.indexOf(';');
    if (semiIdx != -1) {
      s = s.substring(0, semiIdx);
    }
    int colonIdx = s.indexOf(':');
    if (colonIdx != -1) {
      s = s.substring(0, colonIdx);
    }
    int gtIdx = s.indexOf('>');
    if (gtIdx != -1) {
      s = s.substring(0, gtIdx);
    }
    return s.trim();
  }
}

