package lab.sip.client;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Picks a campus LAN IPv4. Skip loopback, link-local, Docker/VM NICs, and the
 * VMware host-only nets called out in server/kamailio/README.md.
 */
final class CampusLan {
  private CampusLan() {}

  static String detect() {
    String best10 = null;
    String bestRfc = null;
    try {
      Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
      if (nics == null) {
        return "";
      }
      while (nics.hasMoreElements()) {
        NetworkInterface nic = nics.nextElement();
        if (skipNic(nic)) {
          continue;
        }
        Enumeration<InetAddress> addrs = nic.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress addr = addrs.nextElement();
          if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
            continue;
          }
          String ip = addr.getHostAddress();
          if (skipAddress(addr, ip)) {
            continue;
          }
          if (ip.startsWith("10.")) {
            if (best10 == null || preferNic(nic)) {
              best10 = ip;
            }
          } else if (bestRfc == null || preferNic(nic)) {
            bestRfc = ip;
          }
        }
      }
    } catch (Exception ignored) {
      return "";
    }
    if (best10 != null) {
      return best10;
    }
    return bestRfc == null ? "" : bestRfc;
  }

  static Target parseServer(String server) {
    String s = server == null ? "" : server.trim();
    if (s.startsWith("sip:") || s.startsWith("SIP:")) {
      s = s.substring(4);
    }
    int cut = s.indexOf(';');
    if (cut >= 0) {
      s = s.substring(0, cut);
    }
    cut = s.indexOf('/');
    if (cut >= 0) {
      s = s.substring(0, cut);
    }
    int colon = s.lastIndexOf(':');
    if (colon > 0 && s.indexOf(':') == colon) {
      String portPart = s.substring(colon + 1);
      if (portPart.matches("\\d{1,5}")) {
        int port = Integer.parseInt(portPart);
        if (port > 0 && port < 65536) {
          return new Target(s.substring(0, colon), port);
        }
      }
    }
    return new Target(s, 5060);
  }

  static boolean probeTcp(String server) {
    Target t = parseServer(server);
    if (t.host.isEmpty()) {
      return false;
    }
    try (Socket socket = new Socket()) {
      socket.connect(new java.net.InetSocketAddress(t.host, t.port), 800);
      return true;
    } catch (SocketTimeoutException e) {
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean skipNic(NetworkInterface nic) {
    try {
      if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()) {
        return true;
      }
    } catch (Exception e) {
      return true;
    }
    String name = (nic.getName() + " " + nic.getDisplayName()).toLowerCase(Locale.ROOT);
    return name.contains("docker")
        || name.contains("vmnet")
        || name.contains("vbox")
        || name.contains("virtualbox")
        || name.contains("vmware")
        || name.contains("hyper-v")
        || name.contains("vethernet")
        || name.contains("utun")
        || name.contains("awdl")
        || name.contains("llw")
        || name.contains("bridge")
        || name.contains("br-")
        || name.contains("veth")
        || name.contains("tun")
        || name.contains("tap")
        || name.contains("lo0")
        || name.contains("loopback");
  }

  private static boolean skipAddress(InetAddress addr, String ip) {
    if (addr.isLinkLocalAddress() || ip.startsWith("169.254.")) {
      return true;
    }
    // VMware host-only / NAT on the signalling host
    if (ip.startsWith("192.168.154.") || ip.startsWith("192.168.204.")) {
      return true;
    }
    return false;
  }

  private static boolean preferNic(NetworkInterface nic) {
    String name = (nic.getName() + " " + nic.getDisplayName()).toLowerCase(Locale.ROOT);
    return name.startsWith("en0")
        || name.contains("wi-fi")
        || name.contains("wifi")
        || name.contains("wlan")
        || name.contains("ethernet")
        || name.startsWith("eth");
  }

  static final class Target {
    final String host;
    final int port;

    Target(String host, int port) {
      this.host = host;
      this.port = port;
    }
  }
}
