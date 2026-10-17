package com.lab.sipim.esl;

import com.lab.sipim.domain.Room;
import com.lab.sipim.repo.RoomRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * FreeSWITCH Event Socket Layer (ESL) Inbound Listener for Phase 5.
 * Subscribes to conference member join/leave events and updates the Room table.
 * Does NOT execute call commands (rooms are entered by client SIP INVITEs).
 */
@Component
public class EslEventListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EslEventListener.class);

    private final RoomRepository roomRepo;

    @Value("${freeswitch.esl.enabled:true}")
    private boolean enabled;

    @Value("${freeswitch.esl.host:127.0.0.1}")
    private String host;

    @Value("${freeswitch.esl.port:8021}")
    private int port;

    @Value("${freeswitch.esl.password:ClueCon}")
    private String password;

    @Value("${freeswitch.esl.reconnect-interval-sec:5}")
    private int reconnectIntervalSec;

    private volatile boolean running = true;
    private volatile boolean connected = false;
    private Thread workerThread;
    private Socket activeSocket;

    public EslEventListener(RoomRepository roomRepo) {
        this.roomRepo = roomRepo;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("FreeSWITCH ESL listener is disabled via configuration");
            return;
        }
        workerThread = new Thread(this::runLoop, "esl-listener-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    @Override
    @PreDestroy
    public void close() {
        running = false;
        connected = false;
        if (activeSocket != null) {
            try {
                activeSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getTarget() {
        return host + ":" + port;
    }

    private void runLoop() {
        log.info("Starting FreeSWITCH ESL background listener targeting {}:{}", host, port);
        while (running) {
            try (Socket socket = new Socket()) {
                activeSocket = socket;
                socket.connect(new InetSocketAddress(host, port), 3000);
                socket.setSoTimeout(30000);
                socket.setKeepAlive(true);

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                // 1. Initial Handshake: Wait for Content-Type: auth/request
                Map<String, String> initialHeaders = readMimeHeaders(reader);
                String contentType = initialHeaders.get("Content-Type");
                if (contentType != null && contentType.contains("auth/request")) {
                    writer.write("auth " + password + "\n\n");
                    writer.flush();

                    Map<String, String> authReplyHeaders = readMimeHeaders(reader);
                    String replyText = authReplyHeaders.get("Reply-Text");
                    if (replyText == null || !replyText.startsWith("+OK")) {
                        log.warn("FreeSWITCH ESL auth failed at {}:{}: {}", host, port, replyText);
                        continue;
                    }
                    log.info("FreeSWITCH ESL authenticated successfully at {}:{}", host, port);
                }

                // 2. Subscribe to conference maintenance events
                writer.write("event plain CUSTOM conference::maintenance CHANNEL_CREATE CHANNEL_DESTROY\n\n");
                writer.flush();
                Map<String, String> subReplyHeaders = readMimeHeaders(reader);
                log.info("FreeSWITCH ESL event subscription response: {}", subReplyHeaders.get("Reply-Text"));

                connected = true;

                // 3. Main event processing loop
                while (running && !socket.isClosed()) {
                    Map<String, String> frameHeaders;
                    try {
                        frameHeaders = readMimeHeaders(reader);
                    } catch (java.net.SocketTimeoutException ste) {
                        // Idle timeout on ESL socket is expected when no conference events occur; keep reading
                        continue;
                    }
                    if (frameHeaders.isEmpty()) {
                        break; // EOF
                    }
                    String clStr = frameHeaders.get("Content-Length");
                    if (clStr != null) {
                        int length = Integer.parseInt(clStr.trim());
                        char[] buf = new char[length];
                        int totalRead = 0;
                        while (totalRead < length) {
                            int r = reader.read(buf, totalRead, length - totalRead);
                            if (r < 0) break;
                            totalRead += r;
                        }
                        String body = new String(buf, 0, totalRead);
                        Map<String, String> eventData = parseEventBody(body);
                        processEvent(eventData);
                    }
                }
            } catch (Exception e) {
                connected = false;
                if (running) {
                    log.warn("FreeSWITCH ESL connection to {}:{} closed/failed ({}). Retrying in {}s...",
                            host, port, e.getMessage(), reconnectIntervalSec);
                    try {
                        Thread.sleep(Math.max(1, reconnectIntervalSec) * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        connected = false;
    }

    private Map<String, String> readMimeHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
            }
        }
        return headers;
    }

    /**
     * Parses key-value lines from a plain text event body.
     */
    public static Map<String, String> parseEventBody(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return map;
        }
        String[] lines = body.split("\\r?\\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                try {
                    // FreeSWITCH occasionally URL-encodes values containing special characters
                    if (val.contains("%")) {
                        val = URLDecoder.decode(val, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignored) {
                }
                map.put(key, val);
            }
        }
        return map;
    }

    /**
     * Processes an ESL event map and updates the database accordingly.
     */
    public void processEvent(Map<String, String> eventData) {
        if (eventData == null || eventData.isEmpty()) {
            return;
        }

        String eventSubclass = eventData.get("Event-Subclass");
        String eventName = eventData.get("Event-Name");

        // We only care about conference::maintenance events
        if ("conference::maintenance".equalsIgnoreCase(eventSubclass)
                || "CUSTOM".equalsIgnoreCase(eventName) && "conference::maintenance".equalsIgnoreCase(eventSubclass)) {

            String action = eventData.get("Action");
            String confName = eventData.get("Conference-Name");
            String sizeStr = eventData.get("Conference-Size");

            if (confName == null || confName.isBlank()) {
                return;
            }

            String roomKey = normalizeConferenceName(confName);
            int size = 0;
            if (sizeStr != null) {
                try {
                    size = Integer.parseInt(sizeStr.trim());
                } catch (NumberFormatException ignored) {
                }
            }

            handleConferenceEvent(roomKey, action, size);
        }
    }

    /**
     * Handles specific conference actions: add-member, del-member, conference-destroy.
     */
    public void handleConferenceEvent(String roomKey, String action, int currentSize) {
        if (roomKey == null || roomKey.isBlank()) {
            return;
        }

        Optional<Room> found = roomRepo.findBySipUri(roomKey);
        if (found.isEmpty()) {
            found = roomRepo.findBySipUri("room-" + roomKey);
        }
        if (found.isEmpty()) {
            try {
                long id = Long.parseLong(roomKey.replace("room-", ""));
                found = roomRepo.findById(id);
            } catch (Exception ignored) {
            }
        }

        if (found.isEmpty()) {
            log.debug("[ESL] Conference event for unknown room: {}, action: {}", roomKey, action);
            return;
        }

        Room r = found.get();
        if ("add-member".equalsIgnoreCase(action)) {
            r.setMemberCount(Math.max(1, currentSize));
            log.info("[ESL] Room {} member joined. Current count: {}", r.getSipUri(), r.getMemberCount());
            roomRepo.save(r);
        } else if ("del-member".equalsIgnoreCase(action)) {
            r.setMemberCount(Math.max(0, currentSize));
            log.info("[ESL] Room {} member left. Remaining count: {}", r.getSipUri(), r.getMemberCount());
            roomRepo.save(r);
        } else if ("conference-destroy".equalsIgnoreCase(action)) {
            r.setMemberCount(0);
            log.info("[ESL] Room {} conference destroyed, reset count to 0", r.getSipUri());
            roomRepo.save(r);
        }
    }

    public static String normalizeConferenceName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("sip:")) {
            s = s.substring(4);
        }
        int atIdx = s.indexOf('@');
        if (atIdx > 0) {
            s = s.substring(0, atIdx);
        }
        return s.trim();
    }
}
