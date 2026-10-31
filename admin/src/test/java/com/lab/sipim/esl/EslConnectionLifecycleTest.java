package com.lab.sipim.esl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lab.sipim.domain.Room;
import com.lab.sipim.repo.RoomRepository;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EslConnectionLifecycleTest {
    private static EslEventListener listener(RoomRepository rooms, int port) {
        EslEventListener listener = new EslEventListener(rooms) {
            @Override public String executeApi(String command) { return "-ERR no snapshot in this listener test"; }
        };
        ReflectionTestUtils.setField(listener, "enabled", true);
        ReflectionTestUtils.setField(listener, "host", "127.0.0.1");
        ReflectionTestUtils.setField(listener, "port", port);
        ReflectionTestUtils.setField(listener, "password", "test-password");
        ReflectionTestUtils.setField(listener, "reconnectIntervalSec", 1);
        return listener;
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String command(BufferedReader reader) throws IOException {
        String command = reader.readLine(); reader.readLine(); return command;
    }

    @Test void unicodeEventAndFollowingEventBothReachTheListener() throws Exception {
        RoomRepository rooms = mock(RoomRepository.class);
        Room room = new Room(); room.setSipUri("room-101");
        when(rooms.findBySipUri("room-101")).thenReturn(Optional.of(room));
        CountDownLatch updates = new CountDownLatch(2);
        when(rooms.save(any(Room.class))).thenAnswer(invocation -> { updates.countDown(); return invocation.getArgument(0); });
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var executor = Executors.newSingleThreadExecutor();
            Future<?> peer = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    write(socket, "Content-Type: auth/request\n\n"); command(input);
                    write(socket, "Content-Type: command/reply\nReply-Text: +OK accepted\n\n");
                    assertThat(command(input)).isEqualTo("event plain CUSTOM conference::maintenance");
                    write(socket, "Content-Type: command/reply\nReply-Text: +OK subscribed\n\n");
                    for (int count : new int[]{1, 2}) {
                        String body = "Event-Subclass: conference::maintenance\nAction: add-member\nConference-Name: room-101\nConference-Size: " + count + "\nCaller-Name: 张同学\n";
                        write(socket, "Content-Type: text/event-plain\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\n\n" + body);
                    }
                    assertThat(updates.await(3, TimeUnit.SECONDS)).isTrue();
                } catch (Exception error) { throw new CompletionException(error); }
            });
            EslEventListener client = listener(rooms, server.getLocalPort());
            try {
                client.start(); peer.get(5, TimeUnit.SECONDS);
                assertThat(room.getMemberCount()).isEqualTo(2);
            } finally { client.close(); executor.shutdownNow(); }
        }
    }

    @Test void rejectedAuthenticationOrSubscriptionNeverReportsConnected() throws Exception {
        for (boolean rejectAuthentication : new boolean[]{true, false}) {
            try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
                var executor = Executors.newSingleThreadExecutor();
                Future<?> peer = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        write(socket, "Content-Type: auth/request\n\n"); command(input);
                        write(socket, "Content-Type: command/reply\nReply-Text: " + (rejectAuthentication ? "-ERR rejected" : "+OK accepted") + "\n\n");
                        if (!rejectAuthentication) {
                            command(input); write(socket, "Content-Type: command/reply\nReply-Text: -ERR subscription rejected\n\n");
                        }
                        assertThat(input.read()).isEqualTo(-1);
                    } catch (Exception error) { throw new CompletionException(error); }
                });
                EslEventListener client = listener(mock(RoomRepository.class), server.getLocalPort());
                try { client.start(); peer.get(4, TimeUnit.SECONDS); assertThat(client.isConnected()).isFalse(); }
                finally { client.close(); executor.shutdownNow(); }
            }
        }
    }

    @Test void eofReconnectsWithBackoffInsteadOfSpinning() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var executor = Executors.newSingleThreadExecutor();
            Future<Long> interval = executor.submit(() -> {
                try (Socket first = server.accept()) {
                    long start = System.nanoTime(); first.close();
                    try (Socket second = server.accept()) { return System.nanoTime() - start; }
                }
            });
            EslEventListener client = listener(mock(RoomRepository.class), server.getLocalPort());
            try { client.start(); assertThat(interval.get(4, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(900)); }
            finally { client.close(); executor.shutdownNow(); }
        }
    }
}
