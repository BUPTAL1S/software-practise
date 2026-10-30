package lab.sip.client;

import java.util.function.Consumer;
import org.pjsip.pjsua2.AccountConfig;
import org.pjsip.pjsua2.AccountVideoConfig;
import org.pjsip.pjsua2.CodecInfo;
import org.pjsip.pjsua2.CodecInfoVector2;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.MediaFormatVideo;
import org.pjsip.pjsua2.MediaConfig;
import org.pjsip.pjsua2.RtcpFbCap;
import org.pjsip.pjsua2.RtcpFbConfig;
import org.pjsip.pjsua2.VidCodecParam;
import org.pjsip.pjsua2.pjmedia_rtcp_fb_type;
import org.pjsip.pjsua2.pjmedia_vid_stream_rc_method;

/** Conservative LAN defaults; decoder capabilities remain unchanged for MCU reception. */
final class VideoMediaPolicy {
  private VideoMediaPolicy() {}

  static void configureAudio(MediaConfig media, MediaTuning tuning) {
    // Keep RTP work independent of the SIP/UI threads. One receiver worker is PJSIP's recommendation.
    media.setHasIoqueue(true);
    media.setThreadCnt(1);
    if (!tuning.tuneAudio()) return;
    media.setAudioFramePtime(20);
    media.setPtime(20);
    media.setSndRecLatency(tuning.captureMs());
    media.setSndPlayLatency(tuning.playbackMs());
    media.setJbInit(tuning.jitterInitialMs());
    media.setJbMinPre(tuning.jitterMinMs());
    media.setJbMaxPre(tuning.jitterMaxPreMs());
    media.setJbMax(tuning.jitterMaxMs());
    // Preserve the native echo canceller, clock rate and codec compatibility.
  }

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
    configureCodecs(endpoint, MediaTuning.defaults(), log);
  }

  static void configureCodecs(Endpoint endpoint, MediaTuning tuning, Consumer<String> log) throws Exception {
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
            encoder.setWidth(tuning.width());
            encoder.setHeight(tuning.height());
            encoder.setFpsNum(tuning.fps());
            encoder.setFpsDenum(1);
            encoder.setAvgBps(tuning.averageBps());
            encoder.setMaxBps(tuning.maximumBps());
            param.setEncMtu(tuning.mtu());
            // Preserve negotiated H.264 fmtp/profile and the decoder's receive limits.
            endpoint.setVideoCodecParam(id, param);
            if (id.regionMatches(true, 0, "H264", 0, 4)) {
              endpoint.videoCodecSetPriority(id, (short) 255);
            }
            log.accept(id + "：发送目标 " + tuning.width() + "×" + tuning.height() + "/" + tuning.fps()
                + "fps，目标 " + tuning.averageBps() / 1000 + "kbps，上限 " + tuning.maximumBps() / 1000
                + "kbps，MTU " + tuning.mtu() + "，实际值以协商与媒体统计为准");
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
