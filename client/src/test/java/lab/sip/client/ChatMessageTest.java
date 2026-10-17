package lab.sip.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class ChatMessageTest {

  @Test
  void testTextChatMessage() {
    ChatMessage msg = ChatMessage.text("1001", "1002", true, "你好，这是测试消息");
    assertEquals("1001", msg.getSender());
    assertEquals("1002", msg.getReceiver());
    assertTrue(msg.isOutgoing());
    assertEquals("你好，这是测试消息", msg.getText());
    assertFalse(msg.isFile());
    assertNull(msg.getFile());
    assertEquals("已发送", msg.getStatus());
    assertNotNull(msg.formatTime());

    msg.setStatus("已送达");
    assertEquals("已送达", msg.getStatus());
  }

  @Test
  void testFileChatMessage() {
    FileMessage fm = new FileMessage("demo.txt", 100, "http://localhost:8080/api/files/1");
    Path local = Paths.get("/tmp/demo.txt");
    ChatMessage msg = ChatMessage.file("1002", "1001", false, fm, local);

    assertEquals("1002", msg.getSender());
    assertEquals("1001", msg.getReceiver());
    assertFalse(msg.isOutgoing());
    assertTrue(msg.isFile());
    assertEquals(fm, msg.getFile());
    assertEquals(local, msg.getLocalPath());
    assertEquals("", msg.getStatus());
  }

  @Test
  void testExtractUser() {
    assertEquals("1001", MainApp.extractUser("\"1001\" <sip:1001@lab>"));
    assertEquals("1001", MainApp.extractUser("sip:1001@lab:5060;transport=tcp"));
    assertEquals("room-101", MainApp.extractUser("sip:room-101@lab"));
    assertEquals("1002", MainApp.extractUser("1002"));
    assertEquals("1003", MainApp.extractUser("<sip:1003@192.168.1.5:5060>"));
    assertEquals("", MainApp.extractUser(null));
    assertEquals("", MainApp.extractUser(""));
  }

  @Test
  void testFifoStatusCorrelation() {
    java.util.List<ChatMessage> list = new java.util.ArrayList<>();
    ChatMessage msg1 = ChatMessage.text("1001", "1002", true, "hello");
    ChatMessage msg2 = ChatMessage.text("1001", "1002", true, "hello");
    list.add(msg1);
    list.add(msg2);

    assertEquals("已发送", msg1.getStatus());
    assertEquals("已发送", msg2.getStatus());

    // First 200 OK arrives for "hello"
    String body = "hello";
    boolean matched = false;
    for (int i = 0; i < list.size(); i++) {
      ChatMessage m = list.get(i);
      if (m.isOutgoing() && ("已发送".equals(m.getStatus()) || m.getStatus().isEmpty())) {
        String expectedBody = m.isFile() ? m.getFile().toWireFormat() : m.getText();
        if (body != null && !body.isEmpty() && expectedBody.equals(body)) {
          m.setStatus("已送达");
          matched = true;
          break;
        }
      }
    }
    assertTrue(matched);
    // Verified: First message received 200 OK and is "已送达", second is still "已发送"
    assertEquals("已送达", msg1.getStatus());
    assertEquals("已发送", msg2.getStatus());

    // Second 200 OK arrives for "hello"
    matched = false;
    for (int i = 0; i < list.size(); i++) {
      ChatMessage m = list.get(i);
      if (m.isOutgoing() && ("已发送".equals(m.getStatus()) || m.getStatus().isEmpty())) {
        String expectedBody = m.isFile() ? m.getFile().toWireFormat() : m.getText();
        if (body != null && !body.isEmpty() && expectedBody.equals(body)) {
          m.setStatus("已送达");
          matched = true;
          break;
        }
      }
    }
    assertTrue(matched);
    // Verified: Both messages are now "已送达" in strict FIFO order
    assertEquals("已送达", msg1.getStatus());
    assertEquals("已送达", msg2.getStatus());
  }

  @Test
  void testIsRoomTarget() {
    assertTrue(MainApp.isRoomTarget("room-101"));
    assertTrue(MainApp.isRoomTarget("sip:room-101@lab"));
    assertTrue(MainApp.isRoomTarget("<sip:room-101@127.0.0.1:5060>"));
    assertTrue(MainApp.isRoomTarget("\"Conference Room\" <sip:room-demo@lab:5060>"));
    assertFalse(MainApp.isRoomTarget("1002"));
    assertFalse(MainApp.isRoomTarget("sip:1002@lab"));
    assertFalse(MainApp.isRoomTarget("<sip:1002@127.0.0.1:5060>"));
    assertFalse(MainApp.isRoomTarget(""));
    assertFalse(MainApp.isRoomTarget("   "));
    assertFalse(MainApp.isRoomTarget(null));
  }

  @Test
  void testReceiptTargetUserMatching() {
    java.util.List<ChatMessage> list = new java.util.ArrayList<>();
    ChatMessage msgA = ChatMessage.text("1001", "1002", true, "你好");
    ChatMessage msgB = ChatMessage.text("1001", "1003", true, "你好");
    list.add(msgA);
    list.add(msgB);

    // 1. When targetUser is empty, should NEVER match any message even if body is identical
    String targetUserEmpty = "";
    String body = "你好";
    boolean matchedEmpty = false;
    for (int i = 0; i < list.size(); i++) {
      ChatMessage m = list.get(i);
      if (m.isOutgoing() && ("已发送".equals(m.getStatus()) || m.getStatus().isEmpty())) {
        if (!targetUserEmpty.isEmpty() && targetUserEmpty.equals(m.getReceiver())) {
          String expectedBody = m.isFile() ? m.getFile().toWireFormat() : m.getText();
          if (body != null && !body.isEmpty() && expectedBody.equals(body)) {
            m.setStatus("已送达");
            matchedEmpty = true;
            break;
          }
        }
      }
    }
    assertFalse(matchedEmpty);
    assertEquals("已发送", msgA.getStatus());
    assertEquals("已发送", msgB.getStatus());

    // 2. When targetUser is 1003, should only match msgB, never touching msgA
    String targetUser1003 = "1003";
    boolean matched1003 = false;
    for (int i = 0; i < list.size(); i++) {
      ChatMessage m = list.get(i);
      if (m.isOutgoing() && ("已发送".equals(m.getStatus()) || m.getStatus().isEmpty())) {
        if (!targetUser1003.isEmpty() && targetUser1003.equals(m.getReceiver())) {
          String expectedBody = m.isFile() ? m.getFile().toWireFormat() : m.getText();
          if (body != null && !body.isEmpty() && expectedBody.equals(body)) {
            m.setStatus("已送达");
            matched1003 = true;
            break;
          }
        }
      }
    }
    assertTrue(matched1003);
    assertEquals("已发送", msgA.getStatus());
    assertEquals("已送达", msgB.getStatus());
  }
}

