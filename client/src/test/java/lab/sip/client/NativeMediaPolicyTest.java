package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.pjsip.pjsua2.*;

/** Native parameter read-back only: no camera, microphone, account, transport or call is opened. */
@EnabledIfSystemProperty(named = "sip.nativePolicyTest", matches = "true")
class NativeMediaPolicyTest {
  private static Map<String,String> fmtp(CodecFmtpVector parameters) {
    Map<String,String> values = new TreeMap<>();
    for (int i=0; i<parameters.size(); i++) {
      CodecFmtp parameter = parameters.get(i);
      values.put(parameter.getName(), parameter.getVal());
    }
    return values;
  }

  @Test void profilesApplyWithoutChangingDecoderOrSdpCapabilities() throws Exception {
    NativeLoader.load();
    Endpoint endpoint = new Endpoint(); EpConfig config = new EpConfig();
    boolean created = false;
    try {
      endpoint.libCreate(); created = true;
      MediaConfig media = config.getMedConfig();
      long clock = media.getClockRate(), echo = media.getEcTailLen();
      VideoMediaPolicy.configureAudio(media, MediaTuning.defaults());
      assertEquals(clock, media.getClockRate()); assertEquals(echo, media.getEcTailLen());
      assertTrue(media.getHasIoqueue()); assertEquals(1, media.getThreadCnt());
      assertEquals(20, media.getAudioFramePtime()); assertEquals(20, media.getPtime());
      assertEquals(40, media.getSndRecLatency()); assertEquals(40, media.getSndPlayLatency());
      assertEquals(40, media.getJbInit()); assertEquals(20, media.getJbMinPre());
      assertEquals(80, media.getJbMaxPre()); assertEquals(160, media.getJbMax());
      config.getLogConfig().setLevel(1); config.getLogConfig().setConsoleLevel(1);
      endpoint.libInit(config);
      VidCodecParam original = endpoint.getVideoCodecParam("H264/97");
      Map<String,String> receiveFmtp, sendFmtp;
      long receiveWidth, receiveHeight;
      try {
        receiveFmtp = fmtp(original.getDecFmtp()); sendFmtp = fmtp(original.getEncFmtp());
        receiveWidth = original.getDecFmt().getWidth(); receiveHeight = original.getDecFmt().getHeight();
      } finally { original.delete(); }
      assertTrue(receiveFmtp.values().contains("42e01e"), receiveFmtp.toString());
      for (String profile : new String[]{"balanced", "motion", "legacy"}) {
        Properties properties = new Properties(); properties.setProperty("sip.media.profile", profile);
        MediaTuning tuning = MediaTuning.read(properties);
        AccountConfig account = new AccountConfig();
        try {
          VideoMediaPolicy.configureAccount(account, tuning);
          assertEquals(profile.equals("legacy") ? 0 : Math.max(tuning.maximumBps(), tuning.averageBps()*3L),
              account.getVideoConfig().getRateControlBandwidth());
        } finally { account.delete(); }
        VideoMediaPolicy.configureCodecs(endpoint, tuning, System.out::println);
        VidCodecParam actual = endpoint.getVideoCodecParam("H264/97");
        try {
          assertEquals(tuning.width(), actual.getEncFmt().getWidth());
          assertEquals(tuning.height(), actual.getEncFmt().getHeight());
          assertEquals(tuning.fps(), actual.getEncFmt().getFpsNum());
          assertEquals(tuning.averageBps(), actual.getEncFmt().getAvgBps());
          assertEquals(tuning.maximumBps(), actual.getEncFmt().getMaxBps());
          assertEquals(tuning.mtu(), actual.getEncMtu());
          assertEquals(receiveFmtp, fmtp(actual.getDecFmtp())); assertEquals(sendFmtp, fmtp(actual.getEncFmtp()));
          assertEquals(receiveWidth, actual.getDecFmt().getWidth()); assertEquals(receiveHeight, actual.getDecFmt().getHeight());
        } finally { actual.delete(); }
      }
      StreamInfo video = new StreamInfo(); VidCodecParam codec = new VidCodecParam();
      try {
        video.setType(pjmedia_type.PJMEDIA_TYPE_VIDEO);
        codec.getDecFmt().setFpsNum(10); codec.getDecFmt().setFpsDenum(1); video.setVidCodecParam(codec);
        VideoMediaPolicy.configureStream(video, MediaTuning.defaults());
        assertEquals(0, video.getJbInit()); assertEquals(0, video.getJbMinPre());
        assertEquals(100, video.getJbMaxPre()); assertEquals(200, video.getJbMax());
        video.setJbInit(17); video.setJbMax(999);
        Properties legacy = new Properties(); legacy.setProperty("sip.media.profile","legacy");
        VideoMediaPolicy.configureStream(video, MediaTuning.read(legacy));
        assertEquals(17, video.getJbInit()); assertEquals(999, video.getJbMax());
      } finally { video.delete(); codec.delete(); }
      System.out.println("PASS native media policy: balanced/motion/legacy, unchanged decoder+fmtp, audio read-back");
    } finally {
      if (created) endpoint.libDestroy();
      config.delete(); endpoint.delete();
    }
  }
}
