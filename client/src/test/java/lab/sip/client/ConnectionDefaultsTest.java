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
    assertEquals("10.21.233.158", settings.sip());
    assertEquals("http://10.21.233.158:8080", settings.http());
  }
  @Test void freshRemoteMachineUsesDeploymentHost() {
    assertEquals("10.21.233.158", ConnectionDefaults.resolve(false, "", "").sip());
  }
  @Test void intentionalRemoteServerOverrideSurvivesRestart() {
    var settings = ConnectionDefaults.resolve(false, "10.0.0.8", "http://10.0.0.8:8088");
    assertEquals("10.0.0.8", settings.sip());
    assertEquals("http://10.0.0.8:8088", settings.http());
  }
}
