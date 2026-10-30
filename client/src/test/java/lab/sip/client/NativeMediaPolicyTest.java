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
      assertEquals(60, media.getSndRecLatency()); assertEquals(60, media.getSndPlayLatency());
      assertEquals(60, media.getJbInit()); assertEquals(20, media.getJbMinPre());
      assertEquals(120, media.getJbMaxPre()); assertEquals(240, media.getJbMax());
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
      System.out.println("PASS native media policy: balanced/motion/legacy, unchanged decoder+fmtp, audio read-back");
    } finally {
      if (created) endpoint.libDestroy();
      config.delete(); endpoint.delete();
    }
  }
}
