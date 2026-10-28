package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;

import com.lab.sipim.domain.Room;
import com.lab.sipim.esl.EslEventListener;
import com.lab.sipim.repo.RoomRepository;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@TestPropertySource(properties = {
        "SIPIM_DB=./target/test-esl.db",
        "sipim.avatar.fs-dir=target/test-esl-avatars",
        "sipim.avatar.upload-dir=target/test-esl-uploads",
        "freeswitch.esl.enabled=false" // disabled by default in test, tested explicitly below
})
class EslEventListenerTest {

    static {
        try {
            Files.deleteIfExists(Path.of("target/test-esl.db"));
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired
    private RoomRepository roomRepo;

    @Autowired
    private EslEventListener eslListener;

    @Test
    void parseEventBodyExtractsKeyValuePairs() {
        String body = """
                Event-Name: CUSTOM
                Event-Subclass: conference%3A%3Amaintenance
                Action: add-member
                Conference-Name: room-101%40video-mcu-3x3
                Conference-Size: 3
                Member-ID: 7
                """;
        Map<String, String> data = EslEventListener.parseEventBody(body);
        assertThat(data.get("Event-Name")).isEqualTo("CUSTOM");
        assertThat(data.get("Event-Subclass")).isEqualTo("conference::maintenance");
        assertThat(data.get("Action")).isEqualTo("add-member");
        assertThat(data.get("Conference-Name")).isEqualTo("room-101@video-mcu-3x3");
        assertThat(data.get("Conference-Size")).isEqualTo("3");
        assertThat(data.get("Member-ID")).isEqualTo("7");
    }

    @Test
    void normalizeConferenceNameStripsDomainAndSip() {
        assertThat(EslEventListener.normalizeConferenceName("room-101@video-mcu-3x3")).isEqualTo("room-101");
        assertThat(EslEventListener.normalizeConferenceName("sip:room-102@lab")).isEqualTo("room-102");
        assertThat(EslEventListener.normalizeConferenceName("room-103")).isEqualTo("room-103");
        assertThat(EslEventListener.normalizeConferenceName(null)).isEmpty();
    }

    @Test
    void processEventUpdatesRoomMemberCountInRepository() {
        // Ensure room-101 exists
        Room room = roomRepo.findBySipUri("room-101").orElseGet(() -> {
            Room r = new Room();
            r.setName("研讨室101");
            r.setSipUri("room-101");
            r.setMaxMembers(8);
            r.setMemberCount(0);
            return roomRepo.save(r);
        });
        room.setMemberCount(0);
        roomRepo.save(room);

        // 1. First member joins (size=1)
        Map<String, String> add1 = Map.of(
                "Event-Name", "CUSTOM",
                "Event-Subclass", "conference::maintenance",
                "Action", "add-member",
                "Conference-Name", "room-101@video-mcu-3x3",
                "Conference-Size", "1"
        );
        eslListener.processEvent(add1);
        Room updated1 = roomRepo.findBySipUri("room-101").orElseThrow();
        assertThat(updated1.getMemberCount()).isEqualTo(1);

        // 2. Second & third member join (size=3)
        Map<String, String> add3 = Map.of(
                "Event-Name", "CUSTOM",
                "Event-Subclass", "conference::maintenance",
                "Action", "add-member",
                "Conference-Name", "room-101",
                "Conference-Size", "3"
        );
        eslListener.processEvent(add3);
        Room updated2 = roomRepo.findBySipUri("room-101").orElseThrow();
        assertThat(updated2.getMemberCount()).isEqualTo(3);

        // 3. Member leaves (size=2)
        Map<String, String> del1 = Map.of(
                "Event-Name", "CUSTOM",
                "Event-Subclass", "conference::maintenance",
                "Action", "del-member",
                "Conference-Name", "room-101",
                "Conference-Size", "2"
        );
        eslListener.processEvent(del1);
        Room updated3 = roomRepo.findBySipUri("room-101").orElseThrow();
        assertThat(updated3.getMemberCount()).isEqualTo(2);

        // 4. Conference destroyed (size=0)
        Map<String, String> destroy = Map.of(
                "Event-Name", "CUSTOM",
                "Event-Subclass", "conference::maintenance",
                "Action", "conference-destroy",
                "Conference-Name", "room-101"
        );
        eslListener.processEvent(destroy);
        Room updated4 = roomRepo.findBySipUri("room-101").orElseThrow();
        assertThat(updated4.getMemberCount()).isEqualTo(0);
    }

    @Test
    void liveSocketHandshakeAndEventStreaming() throws Exception {
        // Spin up a mock FreeSWITCH ESL server on an ephemeral local port
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            CountDownLatch authReceived = new CountDownLatch(1);
            CountDownLatch subReceived = new CountDownLatch(1);
            CountDownLatch clientUpdated = new CountDownLatch(1);

            // Server thread simulating FreeSWITCH Event Socket
            Thread serverThread = new Thread(() -> {
                try (Socket clientSocket = server.accept();
                     BufferedReader r = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter w = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8))) {

                    // 1. Send auth/request
                    w.write("Content-Type: auth/request\n\n");
                    w.flush();

                    // Read auth response
                    String authLine = r.readLine();
                    if (authLine != null && authLine.startsWith("auth ClueCon")) {
                        authReceived.countDown();
                    }
                    // Read trailing empty line
                    r.readLine();

                    // Reply +OK accepted
                    w.write("Content-Type: command/reply\nReply-Text: +OK accepted\n\n");
                    w.flush();

                    // Read event subscription
                    String subLine = r.readLine();
                    if (subLine != null && subLine.contains("event plain")) {
                        subReceived.countDown();
                    }
                    r.readLine();

                    // Reply +OK event listener enabled plain
                    w.write("Content-Type: command/reply\nReply-Text: +OK event listener enabled plain\n\n");
                    w.flush();

                    // Push conference add-member event for room-102
                    String eventBody = """
                            Event-Name: CUSTOM
                            Event-Subclass: conference::maintenance
                            Action: add-member
                            Conference-Name: room-102
                            Conference-Size: 5
                            """;
                    byte[] bytes = eventBody.getBytes(StandardCharsets.UTF_8);
                    w.write("Content-Length: " + bytes.length + "\n");
                    w.write("Content-Type: text/event-plain\n\n");
                    w.write(eventBody);
                    w.flush();

                    clientUpdated.countDown();
                } catch (Exception ignored) {
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            // Create and start a dedicated EslEventListener pointing to this mock server
            EslEventListener liveListener = new EslEventListener(roomRepo);
            ReflectionTestUtils.setField(liveListener, "enabled", true);
            ReflectionTestUtils.setField(liveListener, "host", "127.0.0.1");
            ReflectionTestUtils.setField(liveListener, "port", port);
            ReflectionTestUtils.setField(liveListener, "password", "ClueCon");
            ReflectionTestUtils.setField(liveListener, "reconnectIntervalSec", 1);

            try {
                liveListener.start();

                assertThat(authReceived.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(subReceived.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(clientUpdated.await(5, TimeUnit.SECONDS)).isTrue();

                // Wait up to 3s for DB update
                long deadline = System.currentTimeMillis() + 3000;
                Room r102 = null;
                while (System.currentTimeMillis() < deadline) {
                    r102 = roomRepo.findBySipUri("room-102").orElse(null);
                    if (r102 != null && r102.getMemberCount() == 5) {
                        break;
                    }
                    Thread.sleep(100);
                }
                assertThat(r102).isNotNull();
                assertThat(r102.getMemberCount()).isEqualTo(5);
            } finally {
                liveListener.close();
            }
        }
    }

    @Test
    void duplicateMemberJoinKicksOldMemberAndLateDelDoesNotEraseNewMember() {
        Room room = roomRepo.findBySipUri("room-101").orElseGet(() -> {
            Room r = new Room();
            r.setName("Room 101");
            r.setSipUri("room-101");
            r.setMaxMembers(8);
            r.setMemberCount(0);
            return roomRepo.save(r);
        });
        room.setMemberCount(0);
        roomRepo.save(room);

        RecordingEslListener listener = new RecordingEslListener(roomRepo);
        listener.processEvent(event("add-member", "7", "1001", "1"));
        listener.processEvent(event("add-member", "8", "1001", "1"));
        assertThat(listener.commands).containsExactly("conference room-101 kick 7");

        // A late del-member for the kicked old channel must not remove the new member.
        listener.processEvent(event("del-member", "7", "1001", "1"));
        listener.processEvent(event("add-member", "9", "1001", "1"));
        assertThat(listener.commands).containsExactly(
                "conference room-101 kick 7",
                "conference room-101 kick 8");
    }

    private static Map<String, String> event(String action, String memberId, String caller, String size) {
        return Map.of(
                "Event-Name", "CUSTOM",
                "Event-Subclass", "conference::maintenance",
                "Action", action,
                "Conference-Name", "room-101@video-mcu-3x3",
                "Conference-Size", size,
                "Member-ID", memberId,
                "Caller-Caller-ID-Number", caller);
    }

    private static final class RecordingEslListener extends EslEventListener {
        private final List<String> commands = new ArrayList<>();

        RecordingEslListener(RoomRepository roomRepo) {
            super(roomRepo);
        }

        @Override
        public String executeApi(String command) {
            commands.add(command);
            return "+OK";
        }
    }
}
