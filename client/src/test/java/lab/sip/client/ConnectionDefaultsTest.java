package lab.sip.client;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ConnectionDefaultsTest {
  @Test void serverMachineUsesLoopbackAndKeepsDiscoveredBackendPort() {
    var settings = ConnectionDefaults.resolve(true, "10.21.233.158", "http://localhost:8088");
    assertEquals("127.0.0.1", settings.sip());
    assertEquals("http://localhost:8088", settings.http());
  }
  @Test void copiedLocalPreferencesDoNotConnectRemoteMachineToItself() {
    var settings = ConnectionDefaults.resolve(false, "127.0.0.1", "http://localhost:8088");
    assertEquals("", settings.sip());
    assertEquals("", settings.http());
  }
  @Test void freshRemoteMachineWaitsForDiscoveryInsteadOfShippingAnOldCampusIp() {
    assertEquals("", ConnectionDefaults.resolve(false, "", "").sip());
    assertEquals("", ConnectionDefaults.resolve(false, "", "").http());
  }
  @Test void intentionalRemoteServerOverrideSurvivesRestart() {
    var settings = ConnectionDefaults.resolve(false, "10.0.0.8", "http://10.0.0.8:8088");
    assertEquals("10.0.0.8", settings.sip());
    assertEquals("http://10.0.0.8:8088", settings.http());
  }
}
