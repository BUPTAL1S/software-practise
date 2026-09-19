package lab.sip.client;

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
import org.pjsip.pjsua2.OnCallMediaStateParam;
import org.pjsip.pjsua2.OnCallStateParam;
import org.pjsip.pjsua2.OnIncomingCallParam;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.StringVector;
import org.pjsip.pjsua2.TransportConfig;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsip_inv_state;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsip_transport_type_e;

/** PJSUA2 wrapper: REGISTER + one-to-one audio. No video, no MCU fallback. */
final class SipService {
  interface Events {
    void log(String msg);

    void stateChanged();
  }

  private final Events events;
  private Endpoint endpoint;
  private ClientAccount account;
  private ClientCall activeCall;
  private boolean started;
  private boolean incomingRinging;
  private int boundPort;
  private int lastRegCode;

  SipService(Events events) {
    this.events = events;
  }

  synchronized boolean isStarted() {
    return started;
  }

  synchronized boolean isRegistered() {
    return started && account != null && lastRegCode == pjsip_status_code.PJSIP_SC_OK;
  }

  synchronized boolean hasIncoming() {
    return incomingRinging && activeCall != null;
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
        started = true;
        last = null;
        break;
      } catch (Exception e) {
        last = e;
        closeEndpointQuiet();
        events.log("本机 SIP 端口 " + tryPort + " 不可用，尝试 " + (tryPort + 2) + "：" + e.getMessage());
      }
    }
    if (!started) {
      throw last != null ? last : new IllegalStateException("无法绑定 SIP 端口");
    }
    String contact =
        publicIp == null || publicIp.isBlank() ? "" : "，Contact " + publicIp;
    events.log("引擎已启动，本机 SIP UDP/TCP " + boundPort + contact);
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
    cfg.getRegConfig().setRegistrarUri(sipTarget);
    cfg.getNatConfig().setContactRewriteUse(0);
    cfg.getNatConfig().setViaRewriteUse(0);
    cfg.getNatConfig().setContactUseSrcPort(0);
    cfg.getVideoConfig().setAutoShowIncoming(false);
    cfg.getVideoConfig().setAutoTransmitOutgoing(false);
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
    }
    next.create(cfg);
    events.log("正在 REGISTER " + cfg.getIdUri() + " → " + sipTarget);
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
    synchronized (this) {
      if (account == null || lastRegCode != pjsip_status_code.PJSIP_SC_OK) {
        throw new IllegalStateException("尚未注册成功");
      }
      acc = account;
      next = new ClientCall(acc);
      activeCall = next;
    }
    next.makeCall(dst, new CallOpParam(true));
    events.log("正在呼叫 " + dst);
    events.stateChanged();
  }

  void hangup() {
    hangupQuiet();
    events.log("已挂断");
    events.stateChanged();
  }

  void answer() throws Exception {
    ClientCall call;
    synchronized (this) {
      if (activeCall == null || !incomingRinging) {
        throw new IllegalStateException("没有来电");
      }
      call = activeCall;
      incomingRinging = false;
    }
    CallOpParam prm = new CallOpParam();
    prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
    call.answer(prm);
    events.log("已接听");
    events.stateChanged();
  }

  void shutdown() {
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
    }
    events.stateChanged();
  }

  private void openEndpoint(int port, String publicIp) throws Exception {
    endpoint = new Endpoint();
    endpoint.libCreate();
    EpConfig epConfig = new EpConfig();
    epConfig.getLogConfig().setLevel(3);
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
  }

  private void hangupQuiet() {
    ClientCall call;
    synchronized (this) {
      call = activeCall;
      incomingRinging = false;
      if (call != null) {
        call.localHangup = true;
      }
    }
    if (call == null) {
      return;
    }
    try {
      call.hangup(new CallOpParam());
    } catch (Exception ignored) {
      // already gone; DISCONNECTED will delete
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
      events.log("注册：" + prm.getCode() + " " + prm.getReason());
      events.stateChanged();
    }

    @Override
    public void onIncomingCall(OnIncomingCallParam prm) {
      ClientCall previous;
      synchronized (SipService.this) {
        previous = activeCall;
        activeCall = new ClientCall(this, prm.getCallId());
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
      events.log("来电 callId=" + prm.getCallId() + "，点接听");
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
          events.log(local ? "通话：DISCONNECTED " + detail : "对端已挂断 " + detail);
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
          events.log("通话：" + ci.getStateText());
        }
      } catch (Exception e) {
        events.log("通话状态错误：" + e.getMessage());
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
          }
        }
      } catch (Exception e) {
        events.log("音频连接失败：" + e.getMessage());
      }
    }
  }
}
