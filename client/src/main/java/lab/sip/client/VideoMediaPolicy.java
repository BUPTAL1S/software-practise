package lab.sip.client;

import java.util.function.Consumer;
import org.pjsip.pjsua2.AccountConfig;
import org.pjsip.pjsua2.AccountVideoConfig;
import org.pjsip.pjsua2.CodecInfo;
import org.pjsip.pjsua2.CodecInfoVector2;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.MediaFormatVideo;
import org.pjsip.pjsua2.RtcpFbCap;
import org.pjsip.pjsua2.RtcpFbConfig;
import org.pjsip.pjsua2.VidCodecParam;
import org.pjsip.pjsua2.pjmedia_rtcp_fb_type;
import org.pjsip.pjsua2.pjmedia_vid_stream_rc_method;

/** Conservative LAN defaults; decoder capabilities remain unchanged for MCU reception. */
final class VideoMediaPolicy {
  private VideoMediaPolicy() {}

  static void configureAccount(AccountConfig account) {
    AccountVideoConfig video = account.getVideoConfig();
    // Pace packets on a send thread instead of blocking the capture/encoding thread.
    video.setRateControlMethod(pjmedia_vid_stream_rc_method.PJMEDIA_VID_STREAM_RC_SEND_THREAD);
    video.setRateControlBandwidth(0); // Follow each codec's maximum bitrate.
    video.setStartKeyframeCount(3);
    video.setStartKeyframeInterval(1000);

    RtcpFbConfig feedback = account.getMediaConfig().getRtcpFbConfig();
    // Keep RTP/AVP compatibility with the existing FreeSWITCH SIP profile.
    feedback.setDontUseAvpf(true);
    RtcpFbCap pli = new RtcpFbCap();
    try {
      pli.setCodecId("*");
      pli.setType(pjmedia_rtcp_fb_type.PJMEDIA_RTCP_FB_NACK);
      pli.setParam("pli");
      feedback.getCaps().add(pli);
    } finally {
      pli.delete();
    }
  }

  static void configureCodecs(Endpoint endpoint, Consumer<String> log) throws Exception {
    CodecInfoVector2 codecs = endpoint.videoCodecEnum2();
    try {
      for (int i = 0; i < codecs.size(); i++) {
        CodecInfo info = codecs.get(i);
        String id = info.getCodecId();
        if (info.getPriority() == 0) continue;
        try {
          VidCodecParam param = endpoint.getVideoCodecParam(id);
          try {
            MediaFormatVideo encoder = param.getEncFmt();
            encoder.setWidth(640);
            encoder.setHeight(360);
            encoder.setFpsNum(15);
            encoder.setFpsDenum(1);
            encoder.setAvgBps(500_000);
            encoder.setMaxBps(800_000);
            param.setEncMtu(1200);
            // Preserve negotiated H.264 fmtp/profile and the decoder's receive limits.
            endpoint.setVideoCodecParam(id, param);
            if (id.regionMatches(true, 0, "H264", 0, 4)) {
              endpoint.videoCodecSetPriority(id, (short) 255);
            }
            log.accept(id + "：发送 640×360/15fps，目标 500kbps，上限 800kbps，MTU 1200");
          } finally {
            param.delete();
          }
        } catch (Exception e) {
          log.accept(id + " 参数未应用，保留默认值：" + e.getMessage());
        }
      }
    } finally {
      codecs.delete();
    }
  }
}
