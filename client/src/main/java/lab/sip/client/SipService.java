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
import org.pjsip.pjsua2.StringVector;
import org.pjsip.pjsua2.TransportConfig;
import org.pjsip.pjsua2.pj_log_decoration;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsip_inv_state;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsip_transport_type_e;

/** PJSUA2 wrapper: REGISTER + one-to-one audio. No video, no MCU fallback. */
final class SipService {
  interface Events {
    void log(String msg);

    void log(String category, String msg);

    void sip(boolean outgoing, String summary, String raw);

    void stateChanged();
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
  private boolean started;
  private boolean incomingRinging;
  private int boundPort;
  private String advertisedIp = "";
  private String outboundProxy = "";
  private int lastRegCode;
  private int hangupWatchSeq;
  private boolean byeAnswered;
  private ScheduledFuture<?> hangupWatchTask;

  SipService(Events events) {
    this.events = events;
  }

  synchronized boolean isStarted() {
    return started;
  }

  synchronized boolean isRegistered() {
    return started && account != null && lastRegCode == pjsip_status_code.PJSIP_SC_OK;
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
    cfg.getVideoConfig().setAutoShowIncoming(false);
    cfg.getVideoConfig().setAutoTransmitOutgoing(false);
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
    ClientAccount next = new ClientAccount();
    synchronized (this) {
      account = next;
      lastRegCode = 0;
      outboundProxy = sipTarget;
    }
    next.create(cfg);
    events.log("注册", "正在 REGISTER " + cfg.getIdUri() + " → " + sipTarget);
    events.stateChanged();
  }

  void call(String peerUser, String domain) throws Exception {
    if (!isRegistered()) {
      throw new IllegalStateException("尚未注册成功");
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
      next = new ClientCall(acc);
      activeCall = next;
      proxy = outboundProxy;
    }
    next.makeCall(dst, new CallOpParam(true));
    events.log("通话", "正在呼叫 " + dst + (proxy.isEmpty() ? "" : " 经 " + proxy));
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
    ClientCall call;
    synchronized (this) {
      call = activeCall;
    }
    if (call == null) {
      throw new IllegalStateException("没有来电");
    }
    CallInfo info = call.getInfo();
    int state = info.getState();
    if (state != pjsip_inv_state.PJSIP_INV_STATE_INCOMING
        && state != pjsip_inv_state.PJSIP_INV_STATE_EARLY) {
      throw new IllegalStateException("当前不是来电：" + info.getStateText());
    }
    CallOpParam prm = new CallOpParam();
    prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
    call.answer(prm);
    synchronized (this) {
      if (activeCall == call) {
        incomingRinging = false;
      }
    }
    events.log("通话", "已接听 " + nullToEmpty(info.getRemoteUri()));
    events.stateChanged();
  }

  void shutdown() {
    cancelHangupWatch();
    hangupQuiet();
    ClientAccount acc;
    synchronized (this) {
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
  }

  private final class ClientCall extends Call {
    private volatile boolean localHangup;

    ClientCall(Account acc) {
      super(acc);
    }

    ClientCall(Account acc, int callId) {
      super(acc, callId);
    }

    @Override
    public void onCallState(OnCallStateParam prm) {
      try {
        CallInfo ci = getInfo();
        if (ci.getState() == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
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
          }
        }
      } catch (Exception e) {
        events.log("错误", "音频连接失败：" + e.getMessage());
      }
    }
  }
}
