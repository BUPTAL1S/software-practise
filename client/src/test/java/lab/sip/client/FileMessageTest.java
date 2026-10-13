package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FileMessageTest {

  @Test
  void testEncodeAndDecodeWireFormat() {
    FileMessage msg = new FileMessage("document.pdf", 2048576, "http://192.168.1.10:8080/api/files/42");
    assertEquals("document.pdf", msg.getName());
    assertEquals(2048576, msg.getSize());
    assertEquals("http://192.168.1.10:8080/api/files/42", msg.getUrl());

    String wire = msg.toWireFormat();
    assertEquals("[FILE] document.pdf | 2048576 | http://192.168.1.10:8080/api/files/42", wire);

    FileMessage parsed = FileMessage.tryParse(wire);
    assertNotNull(parsed);
    assertEquals("document.pdf", parsed.getName());
    assertEquals(2048576, parsed.getSize());
    assertEquals("http://192.168.1.10:8080/api/files/42", parsed.getUrl());
  }

  @Test
  void testParseJsonStyleFileNotification() {
    String jsonBody = "[FILE] {\"name\":\"photo.jpg\",\"size\":512000,\"url\":\"/api/files/99\"}";
    FileMessage parsed = FileMessage.tryParse(jsonBody);
    assertNotNull(parsed);
    assertEquals("photo.jpg", parsed.getName());
    assertEquals(512000, parsed.getSize());
    assertEquals("/api/files/99", parsed.getUrl());
  }

  @Test
  void testParseNonFileMessagesReturnsNull() {
    assertNull(FileMessage.tryParse("Hello, how are you?"));
    assertNull(FileMessage.tryParse(""));
    assertNull(FileMessage.tryParse(null));
    assertNull(FileMessage.tryParse("[FILE] invalid format without pipes"));
  }

  @Test
  void testFormatSize() {
    assertEquals("500 B", FileMessage.formatSize(500));
    assertEquals("1.0 KB", FileMessage.formatSize(1024));
    assertEquals("1.5 MB", FileMessage.formatSize((long) (1.5 * 1024 * 1024)));
    assertEquals("2.0 GB", FileMessage.formatSize(2L * 1024 * 1024 * 1024));
  }
}
