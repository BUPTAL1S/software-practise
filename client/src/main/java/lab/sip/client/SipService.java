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
  interface Events {
    void log(String msg);

    void log(String category, String msg);

    void sip(boolean outgoing, String summary, String raw);

    void stateChanged();

    default void instantMessageReceived(String fromUri, String toUri, String body, String contentType) {}

    default void instantMessageStatus(String toUri, String body, int statusCode, String reason) {}

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
  private VideoPreview videoPreview;
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

  VideoDetails queryVideoDetails() {
    synchronized (this) {
      if (!started || endpoint == null) {
        return new VideoDetails(false, "引擎未启动", "无", "无", -1, -1);
      }
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
      try {
        VideoWindow win = videoPreview.getVideoWindow();
        if (win != null) {
          VideoWindowInfo winInfo = win.getInfo();
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
        }
      } catch (Throwable ignored) {
      }
    }
    events.stateChanged();
  }

  synchronized void startPreview() throws Exception {
    if (!started || endpoint == null) {
      throw new IllegalStateException("请先启动引擎");
    }
    if (videoPreview != null) {
      return;
    }
    VideoDetails vd = queryVideoDetails();
    if (!vd.supported || vd.captureDevId < 0) {
      throw new IllegalStateException("未检测到可用的视频输入设备");
    }
    videoPreview = new VideoPreview(vd.captureDevId);
    videoPreview.start(new VideoPreviewOpParam());
    try {
      VideoWindow win = videoPreview.getVideoWindow();
      if (win != null) {
        MediaSize sz = new MediaSize();
        sz.setW(480);
        sz.setH(320);
        try {
          win.setSize(sz);
        } catch (Throwable ignored) {
        }
        win.Show(true);
        if (previewMirrorEnabled) {
          try {
            VideoWindowInfo winInfo = win.getInfo();
            if (winInfo != null && winInfo.getWinHandle() != null
                && winInfo.getWinHandle().getHandle() != null) {
              long hwnd = winInfo.getWinHandle().getHandle().getWindow();
              NativeMirror.applyPreviewMirror(hwnd, "摄像头预览 (镜像)");
            }
          } catch (Throwable t) {
            events.log("系统", "提示: 原生图层镜像应用: " + t.getMessage());
          }
        }
      }
    } catch (Exception ignored) {
    }
    events.log("系统", "本地摄像头预览已开启 (" + vd.captureDev + (previewMirrorEnabled ? " · 镜像模式" : " · 真实模式") + ")");
    events.stateChanged();
  }

  synchronized void stopPreview() {
    if (videoPreview != null) {
      VideoPreview vp = videoPreview;
      videoPreview = null;
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
    if (call == null) {
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
    int port = preferredPort <= 0 ? 5060 : preferredPort;
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
    ClientAccount previous;
    synchronized (this) {
      previous = account;
      account = null;
      lastRegCode = 0;
    }
    if (previous != null) {
      try {
        previous.shutdown();
      } catch (Exception ignored) {
        // replacing account
      }
      try {
        previous.delete();
      } catch (Exception ignored) {
        // replacing account
      }
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
      cfg.getVideoConfig().setAutoShowIncoming(true);
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
    ClientAccount next = new ClientAccount(user);
    synchronized (this) {
      clearBuddiesLocked();
      account = next;
      lastRegCode = 0;
      outboundProxy = sipTarget;
    }
    next.create(cfg);
    events.log("注册", "正在 REGISTER " + cfg.getIdUri() + " → " + sipTarget);
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
      next = new ClientCall(acc, dst);
      activeCall = next;
      proxy = outboundProxy;
    }
    CallOpParam prm = new CallOpParam(true);
    prm.getOpt().setTextCount(0);
    if (!video) {
      prm.getOpt().setVideoCount(0);
    }
    next.makeCall(dst, prm);
    events.log(
        "通话",
        "正在发起" + (video ? "视频" : "语音") + "呼叫 " + dst + (proxy.isEmpty() ? "" : " 经 " + proxy));
    events.stateChanged();
  }

  void hangup() {
    ClientCall call;
    String proxy;
    synchronized (this) {
      call = activeCall;
      proxy = outboundProxy;
    }
    if (call == null) {
      events.log("通话", "当前没有通话");
      events.stateChanged();
      return;
    }
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
    stopPreview();
    cancelHangupWatch();
    hangupQuiet();
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
        // shutting down
      }
      try {
        acc.delete();
      } catch (Exception ignored) {
        // shutting down
      }
    }
    synchronized (this) {
      closeEndpointQuiet();
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
    endpoint.libInit(epConfig);
    createTransport(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, port, publicIp);
    createTransport(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, port, publicIp);
    endpoint.libStart();
  }

  private void createTransport(int type, int port, String publicIp) throws Exception {
    TransportConfig transport = new TransportConfig();
    transport.setPort(port);
    if (publicIp != null && !publicIp.isBlank()) {
      transport.setPublicAddress(publicIp);
    }
    endpoint.transportCreate(type, transport);
  }

  private void closeEndpointQuiet() {
    if (endpoint != null) {
      try {
        endpoint.libDestroy();
      } catch (Exception ignored) {
        // shutting down
      }
      try {
        endpoint.delete();
      } catch (Exception ignored) {
        // shutting down
      }
      endpoint = null;
    }
    // libDestroy 会释放 native LogWriter，这里只丢掉 Java 引用。
    logWriter = null;
  }

  private ClientCall hangupQuiet() {
    return hangupQuiet(true);
  }

  private ClientCall hangupQuiet(boolean cancelWatch) {
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
    try {
      call.hangup(new CallOpParam());
    } catch (Exception ignored) {
      // already gone; DISCONNECTED will delete
    }
    return call;
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
    @Override
    public void write(LogEntry entry) {
      try {
        String msg = entry.getMsg();
        if (msg == null || msg.isBlank()) {
          return;
        }
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
      ClientCall previous;
      ClientCall incoming;
      synchronized (SipService.this) {
        previous = activeCall;
        incoming = new ClientCall(this, prm.getCallId());
        activeCall = incoming;
        incomingRinging = true;
      }
      if (previous != null) {
        previous.localHangup = true;
        try {
          previous.hangup(new CallOpParam());
        } catch (Exception ignored) {
          // previous call going away
        }
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
    private volatile boolean localHangup;
    private volatile boolean callEndedDispatched;

    ClientCall(Account acc, String dialedDst) {
      super(acc);
      this.isOutgoing = true;
      this.dialedDst = dialedDst != null ? dialedDst : "";
    }

    ClientCall(Account acc, int callId) {
      super(acc, callId);
      this.isOutgoing = false;
      this.dialedDst = "";
    }

    @Override
    public void onCallState(OnCallStateParam prm) {
      try {
        CallInfo ci = getInfo();
        int state = ci.getState();
        if (state == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
          if (connectTime == null) {
            connectTime = java.time.Instant.now();
            events.log("通话", "通话已接通确认 (开始计时)");
          }
        } else if (state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
          boolean local = localHangup;
          synchronized (SipService.this) {
            if (activeCall == this) {
              activeCall = null;
              incomingRinging = false;
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
      try {
        CallInfo ci = getInfo();
        for (int i = 0; i < ci.getMedia().size(); i++) {
          CallMediaInfo media = ci.getMedia().get(i);
          if (media.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO) {
            AudioMedia audio = getAudioMedia(i);
            endpoint.audDevManager().getCaptureDevMedia().startTransmit(audio);
            audio.startTransmit(endpoint.audDevManager().getPlaybackDevMedia());
            events.log("通话", "音频已接通");

          } else if (media.getType() == pjmedia_type.PJMEDIA_TYPE_VIDEO) {
            if (media.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
              int winId = media.getVideoIncomingWindowId();
              if (winId >= 0) {
                try {
                  VideoWindow vidWin = new VideoWindow(winId);
                  MediaSize sz = new MediaSize();
                  sz.setW(640);
                  sz.setH(360);
                  try {
                    vidWin.setSize(sz);
                  } catch (Throwable ignored) {
                  }
                  vidWin.Show(true);
                  events.log("通话", "双向视频流已接通，已拉起独立原生视频窗口 (窗口 ID=" + winId + ")");
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
      }
    }

    @Override
    public void onInstantMessage(OnInstantMessageParam prm) {
      String fromUri = nullToEmpty(prm.getFromUri());
      String toUri = nullToEmpty(prm.getToUri());
      String body = nullToEmpty(prm.getMsgBody());
      String contentType = nullToEmpty(prm.getContentType());
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

