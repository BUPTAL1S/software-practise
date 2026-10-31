package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.lab.sipim.domain.Room;
import com.lab.sipim.esl.EslEventListener;
import com.lab.sipim.repo.RoomRepository;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Isolated ESL wire fixture: no database, calls or running FreeSWITCH are touched. */
class EslRoomCountRecoveryTest {
  @Test void startupClearsPersistedFiveWhenNoConferenceExists() throws Exception {
    verifyRecovery(false);
  }

  @Test void periodicSnapshotRepairsMissedLeaveEvent() throws Exception {
    verifyRecovery(true);
  }

  @Test void failedOrIncompleteSnapshotDoesNotEraseLiveCount() {
    Room room = new Room(); room.setSipUri("room-101"); room.setMemberCount(5);
    RoomRepository repo = mock(RoomRepository.class);
    when(repo.findAll()).thenReturn(List.of(room));
    EslEventListener listener = new EslEventListener(repo);
    assertThat(listener.reconcileConferenceSnapshot("-ERR timed out")).isFalse();
    assertThat(listener.reconcileConferenceSnapshot("<conferences><conference name=\"room-101\"/></conferences>")).isFalse();
    assertThat(room.getMemberCount()).isEqualTo(5);
    verify(repo, never()).save(any());
  }

  private void verifyRecovery(boolean missedLeave) throws Exception {
    Room room = new Room(); room.setId(1L); room.setSipUri("room-101"); room.setMemberCount(5);
    RoomRepository repo = mock(RoomRepository.class);
    when(repo.findAll()).thenReturn(List.of(room));
    CountDownLatch initial = new CountDownLatch(1), empty = new CountDownLatch(1);
    when(repo.save(any(Room.class))).thenAnswer(invocation -> {
      Room saved = invocation.getArgument(0);
      if (saved.getMemberCount() == (missedLeave ? 1 : 0)) initial.countDown();
      if (saved.getMemberCount() == 0) empty.countDown();
      return saved;
    });
    try (Peer peer = new Peer()) {
      if (missedLeave) peer.snapshot.set("<conferences><conference name=\"room-101\" member-count=\"1\">"
          + "<members><member><id>7</id><caller_id_name>测试成员</caller_id_name>"
          + "<caller_id_number>1098</caller_id_number></member></members></conference></conferences>");
      EslEventListener listener = new EslEventListener(repo);
      ReflectionTestUtils.setField(listener, "enabled", true);
      ReflectionTestUtils.setField(listener, "host", "127.0.0.1");
      ReflectionTestUtils.setField(listener, "port", peer.server.getLocalPort());
      ReflectionTestUtils.setField(listener, "password", "fixture-only");
      ReflectionTestUtils.setField(listener, "reconnectIntervalSec", 1);
      try {
        listener.start();
        assertThat(initial.await(4, TimeUnit.SECONDS))
            .as("Startup must reconcile the persisted count of five without needing an add/leave event").isTrue();
        if (missedLeave) {
          peer.snapshot.set("<conferences/>");
          assertThat(empty.await(8, TimeUnit.SECONDS)).as("Periodic snapshot must repair a missed leave event").isTrue();
        }
        assertThat(room.getMemberCount()).isEqualTo(0);
      } finally { listener.close(); }
    }
  }

  static final class Peer implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
    final AtomicReference<String> snapshot = new AtomicReference<>("<conferences/>");
    final List<Socket> sockets = new CopyOnWriteArrayList<>();
    final ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
    Peer() throws IOException {
      pool.submit(() -> {
        while (!server.isClosed()) try {
          Socket socket = server.accept(); sockets.add(socket); pool.submit(() -> serve(socket));
        } catch (IOException stopped) { break; }
      });
    }
    void serve(Socket socket) {
      try (socket) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream output = socket.getOutputStream();
        output.write("Content-Type: auth/request\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) continue;
          if (line.startsWith("api conference xml_list")) {
            byte[] bytes = snapshot.get().getBytes(StandardCharsets.UTF_8);
            output.write(("Content-Type: api/response\nContent-Length: " + bytes.length + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.write(bytes); output.flush();
          } else {
            output.write("Content-Type: command/reply\nReply-Text: +OK\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
          }
        }
      } catch (IOException stopped) { }
    }
    public void close() throws IOException {
      server.close(); for (Socket socket : sockets) socket.close(); pool.shutdownNow();
    }
  }
}
