package lab.sip.client;

/** Immutable snapshot of PJSIP video codec/device capabilities. */
class SipVideoDetails {
  final boolean supported;
  final String codec;
  final String captureDev;
  final String renderDev;
  final int captureDevId;
  final int renderDevId;

  SipVideoDetails(
      boolean supported,
      String codec,
      String captureDev,
      String renderDev,
      int captureDevId,
      int renderDevId) {
    this.supported = supported;
    this.codec = codec;
    this.captureDev = captureDev;
    this.renderDev = renderDev;
    this.captureDevId = captureDevId;
    this.renderDevId = renderDevId;
  }
}
