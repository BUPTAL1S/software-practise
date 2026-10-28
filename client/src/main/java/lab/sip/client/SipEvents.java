package lab.sip.client;

/** Callbacks emitted by the SIP service. Keeping this API separate lets callers
 * depend on the signalling contract without depending on PJSUA2 internals. */
interface SipEvents {
  void log(String msg);

  void log(String category, String msg);

  void sip(boolean outgoing, String summary, String raw);

  void stateChanged();

  default void instantMessageReceived(String fromUri, String toUri, String body, String contentType) {}

  default void instantMessageStatus(String toUri, String body, int statusCode, String reason) {}

  default void videoAvatarChanged(String fromUri, boolean off) {}

  default void callEnded(
      String callId,
      String caller,
      String calleeOrRoom,
      java.time.Instant startedAt,
      long durationSec,
      boolean wasConnected) {
    callEnded(caller, calleeOrRoom, startedAt, durationSec, wasConnected);
  }

  default void callEnded(
      String caller,
      String calleeOrRoom,
      java.time.Instant startedAt,
      long durationSec,
      boolean wasConnected) {}
}
