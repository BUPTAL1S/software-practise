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
    private final java.util.Map<String, java.util.Map<String, Integer>> conferenceMemberMap = new java.util.concurrent.ConcurrentHashMap<>();

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
            String memberIdStr = eventData.get("Member-ID");
            String callerNumber = eventData.get("Caller-Caller-ID-Number");

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

            if (memberIdStr != null && callerNumber != null && !callerNumber.isBlank()) {
                try {
                    int mId = Integer.parseInt(memberIdStr.trim());
                    String cleanCaller = callerNumber.trim();
                    if ("add-member".equalsIgnoreCase(action)) {
                        conferenceMemberMap.computeIfAbsent(roomKey, k -> new java.util.concurrent.ConcurrentHashMap<>())
                                .put(cleanCaller, mId);
                    } else if ("del-member".equalsIgnoreCase(action)) {
                        java.util.Map<String, Integer> map = conferenceMemberMap.get(roomKey);
                        if (map != null) {
                            map.remove(cleanCaller);
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            if ("conference-destroy".equalsIgnoreCase(action)) {
                conferenceMemberMap.remove(roomKey);
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

    public String executeApi(String command) {
        if (!enabled) {
            return "-ERR ESL disabled";
        }
        try (Socket s = new Socket(host, port);
             java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
             java.io.BufferedWriter out = new java.io.BufferedWriter(new java.io.OutputStreamWriter(s.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8))) {

            s.setSoTimeout(3000);
            readMimeHeaders(in);
            out.write("auth " + password + "\n\n");
            out.flush();
            readMimeHeaders(in);

            out.write("api " + command + "\n\n");
            out.flush();
            java.util.Map<String, String> headers = readMimeHeaders(in);
            String clStr = headers.get("Content-Length");
            if (clStr != null) {
                int len = Integer.parseInt(clStr.trim());
                char[] buf = new char[len];
                int total = 0;
                while (total < len) {
                    int r = in.read(buf, total, len - total);
                    if (r < 0) break;
                    total += r;
                }
                return new String(buf, 0, total).trim();
            }
            return headers.getOrDefault("Reply-Text", "+OK");
        } catch (Exception e) {
            log.warn("Failed to execute ESL api command '{}': {}", command, e.getMessage());
            return "-ERR " + e.getMessage();
        }
    }

    public Integer lookupMemberId(String confName, String sipId) {
        if (confName == null || confName.isBlank() || sipId == null || sipId.isBlank()) {
            return null;
        }
        String cleanSip = sipId.trim();
        java.util.Map<String, Integer> members = conferenceMemberMap.get(confName);
        if (members != null && members.containsKey(cleanSip)) {
            return members.get(cleanSip);
        }

        String listOut = executeApi("conference " + confName + " list");
        if (listOut != null && !listOut.startsWith("-ERR") && listOut.contains(";")) {
            for (String line : listOut.split("\\r?\\n")) {
                String[] parts = line.split(";");
                if (parts.length >= 5) {
                    try {
                        int mId = Integer.parseInt(parts[0].trim());
                        String callerNumber = parts[4].trim();
                        conferenceMemberMap.computeIfAbsent(confName, k -> new java.util.concurrent.ConcurrentHashMap<>())
                                .put(callerNumber, mId);
                        if (cleanSip.equalsIgnoreCase(callerNumber)) {
                            return mId;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return null;
    }

    public boolean setMemberVmute(String roomUri, String sipId, boolean mute) {
        String confName = normalizeConferenceName(roomUri);
        String cleanSip = (sipId != null) ? sipId.trim() : "";
        Integer memberId = lookupMemberId(confName, cleanSip);

        String action = mute ? "vmute" : "unvmute";
        if (memberId == null) {
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
            }
            memberId = lookupMemberId(confName, cleanSip);
        }

        if (memberId == null) {
            log.warn("[ESL] Conference {} cannot {} member {}: integer member ID not found in conference list", confName, action, cleanSip);
            return false;
        }

        String cmd = "conference " + confName + " " + action + " " + memberId;
        String res = executeApi(cmd);
        log.info("[ESL] Conference {} member {} (id={}) {} -> {}", confName, cleanSip, memberId, action, res);
        boolean ok = (res != null && res.contains("+OK"));
        if (ok && mute) {
            String avatarPath = "/tmp/freeswitch-custom/avatars/" + cleanSip + ".png";
            executeApi("conference " + confName + " vid-mute-img " + memberId + " " + avatarPath);
        }
        return ok;
    }

    public boolean captureConferenceCanvas(String roomUri, String targetPngPath) {
        String confName = normalizeConferenceName(roomUri);
        String res = executeApi("conference " + confName + " vid-write-png " + targetPngPath);
        log.info("[ESL] Conference {} canvas capture to {} -> {}", confName, targetPngPath, res);
        return res != null && res.contains("+OK");
    }
}
