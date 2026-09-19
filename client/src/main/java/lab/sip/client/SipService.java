package lab.sip.client;

import java.util.function.Consumer;
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
import org.pjsip.pjsua2.TransportConfig;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsip_inv_state;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsip_transport_type_e;

/** PJSUA2 wrapper: REGISTER + one-to-one audio. No video, no MCU fallback. */
final class SipService {
  private final Consumer<String> status;
  private Endpoint endpoint;
  private ClientAccount account;
  private ClientCall activeCall;
  private boolean started;

  SipService(Consumer<String> status) {
    this.status = status;
  }

  synchronized void start(int sipPort) throws Exception {
    if (started) {
      return;
    }
    NativeLoader.load();
    endpoint = new Endpoint();
    endpoint.libCreate();
    EpConfig epConfig = new EpConfig();
    epConfig.getLogConfig().setLevel(3);
    endpoint.libInit(epConfig);
    TransportConfig transport = new TransportConfig();
    transport.setPort(sipPort);
    endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transport);
    endpoint.libStart();
    started = true;
    status.accept("引擎已启动，本机 SIP UDP " + sipPort);
  }

  synchronized void register(String server, String user, String password, String domain)
      throws Exception {
    if (!started) {
      throw new IllegalStateException("先启动引擎");
    }
    if (account != null) {
      account.delete();
      account = null;
    }
    String host = server.trim();
    if (host.startsWith("sip:")) {
      host = host.substring(4);
    }
    AccountConfig cfg = new AccountConfig();
    cfg.setIdUri("sip:" + user + "@" + domain);
    cfg.getRegConfig().setRegistrarUri("sip:" + host);
    if (password != null && !password.isEmpty()) {
      cfg.getSipConfig().getAuthCreds().add(new AuthCredInfo("digest", "*", user, 0, password));
    }
    account = new ClientAccount();
    account.create(cfg);
    status.accept("正在 REGISTER " + cfg.getIdUri() + " → " + host);
  }

  synchronized void call(String peerUser, String domain) throws Exception {
    if (account == null) {
      throw new IllegalStateException("尚未注册");
    }
    hangupQuiet();
    String dst = peerUser.trim();
    if (!dst.startsWith("sip:")) {
      dst = "sip:" + dst + "@" + domain;
    }
    activeCall = new ClientCall(account);
    CallOpParam prm = new CallOpParam(true);
    activeCall.makeCall(dst, prm);
    status.accept("正在呼叫 " + dst);
  }

  synchronized void hangup() throws Exception {
    hangupQuiet();
    status.accept("已挂断");
  }

  synchronized void answer() throws Exception {
    if (activeCall == null) {
      throw new IllegalStateException("没有来电");
    }
    CallOpParam prm = new CallOpParam();
    prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
    activeCall.answer(prm);
    status.accept("已接听");
  }

  synchronized void shutdown() {
    try {
      hangupQuiet();
    } catch (Exception ignored) {
      // shutting down
    }
    if (account != null) {
      account.delete();
      account = null;
    }
    if (endpoint != null) {
      try {
        endpoint.libDestroy();
      } catch (Exception ignored) {
        // shutting down
      }
      endpoint.delete();
      endpoint = null;
    }
    started = false;
  }

  private void hangupQuiet() throws Exception {
    if (activeCall != null) {
      try {
        activeCall.hangup(new CallOpParam());
      } finally {
        activeCall.delete();
        activeCall = null;
      }
    }
  }

  private final class ClientAccount extends Account {
    @Override
    public void onRegState(OnRegStateParam prm) {
      status.accept("注册：" + prm.getCode() + " " + prm.getReason());
    }

    @Override
    public void onIncomingCall(OnIncomingCallParam prm) {
      try {
        hangupQuiet();
        activeCall = new ClientCall(this, prm.getCallId());
        status.accept("来电 callId=" + prm.getCallId() + "，点接听");
      } catch (Exception e) {
        status.accept("来电处理失败：" + e.getMessage());
      }
    }
  }

  private final class ClientCall extends Call {
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
        status.accept("通话：" + ci.getStateText());
        if (ci.getState() == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
          activeCall = null;
        }
      } catch (Exception e) {
        status.accept("通话状态错误：" + e.getMessage());
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
        status.accept("音频连接失败：" + e.getMessage());
      }
    }
  }
}
