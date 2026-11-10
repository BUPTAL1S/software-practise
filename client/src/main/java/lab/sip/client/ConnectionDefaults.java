package lab.sip.client;

import java.net.URI;

/** Local/saved configuration; a fresh remote client obtains its host through LAN discovery. */
final class ConnectionDefaults {
  record Settings(String sip, String http) {}

  static Settings resolve(boolean localService, String savedSip, String savedHttp) {
    String sip = localService ? "127.0.0.1" : "";
    if (!localService && savedSip != null && !savedSip.isBlank() && !loopback(host(savedSip))) {
      sip = savedSip.trim();
    }
    String http = sip.isBlank() ? "" : "http://" + host(sip) + ":8080";
    if (savedHttp != null && !savedHttp.isBlank()) {
      String savedHost = host(savedHttp);
      if (savedHost.equalsIgnoreCase(host(sip)) || (localService && loopback(savedHost))) http = savedHttp;
    }
    return new Settings(sip, http);
  }

  private static String host(String address) {
    try {
      String host = URI.create(address.contains("://") ? address : "http://" + address).getHost();
      return host == null ? "" : host;
    } catch (IllegalArgumentException e) { return ""; }
  }

  private static boolean loopback(String host) {
    return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
  }
}
