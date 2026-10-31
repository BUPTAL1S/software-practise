package lab.sip.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Camera status belongs to one SIP dialog; sequence numbers reject reordered updates. */
record VideoStateMessage(String callId, long sequence, boolean enabled) {
  static final String PREFIX = "[VIDEO_STATE_V1] ";
  String encode() {
    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(callId.getBytes(StandardCharsets.UTF_8))
        + " " + sequence + " " + (enabled ? "1" : "0");
  }
  static VideoStateMessage parse(String body) {
    if (body == null || !body.startsWith(PREFIX) || body.length() > 2048) return null;
    try {
      String[] fields = body.substring(PREFIX.length()).split(" ", -1);
      if (fields.length != 3 || !(fields[2].equals("0") || fields[2].equals("1"))) return null;
      long sequence = Long.parseLong(fields[1]);
      String id = new String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8);
      return sequence < 0 || id.isBlank() ? null : new VideoStateMessage(id, sequence, fields[2].equals("1"));
    } catch (IllegalArgumentException malformed) { return null; }
  }
  boolean appliesTo(String dialog, String peer, String sender, long previousSequence) {
    return callId.equals(dialog) && !SipUri.user(peer).isBlank()
        && SipUri.user(peer).equalsIgnoreCase(SipUri.user(sender)) && sequence > previousSequence;
  }
}
