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
    private java.util.concurrent.ScheduledExecutorService reconciliationWorker;
    private final java.util.Map<String, java.util.Map<String, Integer>> conferenceMemberMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, java.util.Map<String, Integer>> conferenceJoinTimes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object memberLock = new Object();
    private final java.util.concurrent.ExecutorService commandExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "esl-command");
                thread.setDaemon(true);
                return thread;
            });

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lab.sipim.service.RoomPresenceRegistry presence;

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
        reconciliationWorker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "esl-room-reconciliation");
            thread.setDaemon(true);
            return thread;
        });
        reconciliationWorker.scheduleWithFixedDelay(() -> {
            if (running && connected) refreshConferenceState();
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        workerThread.start();
    }

    @Override
    @PreDestroy
    public void close() {
        running = false;
        connected = false;
        if (reconciliationWorker != null) reconciliationWorker.shutdownNow();
        commandExecutor.shutdownNow();
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
                // Event subscription does not replay existing members or prior room destruction.
                reconciliationWorker.execute(this::refreshConferenceState);

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
                    Integer oldId = null;
                    synchronized (memberLock) {
                        java.util.Map<String, Integer> map =
                                conferenceMemberMap.computeIfAbsent(
                                        roomKey, k -> new java.util.concurrent.ConcurrentHashMap<>());
                        java.util.Map<String, Integer> joins =
                                conferenceJoinTimes.computeIfAbsent(
                                        roomKey, k -> new java.util.concurrent.ConcurrentHashMap<>());
                        if ("add-member".equalsIgnoreCase(action)) {
                            oldId = map.put(cleanCaller, mId);
                            joins.put(cleanCaller, 0);
                        } else if ("del-member".equalsIgnoreCase(action)) {
                            // Only remove the entry for the member that actually left. A late
                            // del-member event for the kicked old channel must not erase the new one.
                            map.remove(cleanCaller, mId);
                            joins.remove(cleanCaller);
                        }
                    }
                    if (oldId != null && oldId != mId) {
                        // FreeSWITCH does not enforce one member per caller number. If a
                        // client was killed, its old channel stays in the conference and the
                        // same user would appear twice after reconnecting.
                        final int previous = oldId;
                        commandExecutor.execute(() -> {
                            String result = executeApi("conference " + roomKey + " kick " + previous);
                            log.info(
                                    "[ESL] Duplicate member {} in {}: kicked old id {} for new id {} -> {}",
                                    cleanCaller, roomKey, previous, mId, result);
                        });
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            if ("conference-destroy".equalsIgnoreCase(action)) {
                synchronized (memberLock) {
                    conferenceMemberMap.remove(roomKey);
                    conferenceJoinTimes.remove(roomKey);
                }
                if (presence != null) {
                    presence.forgetRoom(roomKey);
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

    private synchronized void refreshConferenceState() {
        if (reconcileConferenceSnapshot(executeApi("conference xml_list"))) {
            cleanupStaleMembers();
        }
    }

    private void cleanupStaleMembers() {
        if (presence == null || presence.startupAgeMillis() < 20_000) {
            return;
        }
        final long heartbeatTtlMs = 15_000;
        final int minimumJoinSeconds = 30;
        java.util.List<String[]> stale = new ArrayList<>();
        synchronized (memberLock) {
            for (var roomEntry : conferenceMemberMap.entrySet()) {
                String roomKey = roomEntry.getKey();
                java.util.Map<String, Integer> joins = conferenceJoinTimes.get(roomKey);
                for (var member : roomEntry.getValue().entrySet()) {
                    String caller = member.getKey();
                    Integer joinTime = joins == null ? null : joins.get(caller);
                    if (joinTime == null || joinTime < minimumJoinSeconds) {
                        continue;
                    }
                    if (!presence.isFresh(roomKey, caller, heartbeatTtlMs)) {
                        stale.add(new String[] {roomKey, caller, String.valueOf(member.getValue())});
                    }
                }
            }
            for (String[] item : stale) {
                java.util.Map<String, Integer> members = conferenceMemberMap.get(item[0]);
                if (members != null) {
                    members.remove(item[1], Integer.valueOf(item[2]));
                }
                java.util.Map<String, Integer> joins = conferenceJoinTimes.get(item[0]);
                if (joins != null) {
                    joins.remove(item[1]);
                }
            }
        }
        for (String[] item : stale) {
            presence.forget(item[0], item[1]);
            commandExecutor.execute(() -> {
                String result = executeApi("conference " + item[0] + " kick " + item[2]);
                log.info("[ESL] Removed stale member {} from {} (id {}) -> {}",
                        item[1], item[0], item[2], result);
            });
        }
    }

    /** Apply only a complete, valid snapshot. API failures must never zero live room counts. */
    public boolean reconcileConferenceSnapshot(String xml) {
        if (xml == null || !xml.stripLeading().startsWith("<")) return false;
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml)));
            if (!"conferences".equals(document.getDocumentElement().getTagName())) return false;
            Map<String, Integer> counts = new HashMap<>();
            Map<String, Map<String, Integer>> memberMaps = new HashMap<>();
            Map<String, Map<String, Integer>> joinTimeMaps = new HashMap<>();
            var conferences = document.getDocumentElement().getElementsByTagName("conference");
            for (int i = 0; i < conferences.getLength(); i++) {
                var conference = (org.w3c.dom.Element) conferences.item(i);
                String name = normalizeConferenceName(conference.getAttribute("name"));
                int count = Integer.parseInt(conference.getAttribute("member-count"));
                if (name.isBlank() || count < 0) return false;
                counts.put(name, count);
                Map<String, Integer> members = new java.util.concurrent.ConcurrentHashMap<>();
                Map<String, Integer> joins = new java.util.concurrent.ConcurrentHashMap<>();
                var nodes = conference.getElementsByTagName("member");
                for (int j = 0; j < nodes.getLength(); j++) {
                    var member = (org.w3c.dom.Element) nodes.item(j);
                    var ids = member.getElementsByTagName("id");
                    var users = member.getElementsByTagName("caller_id_number");
                    var joinTimes = member.getElementsByTagName("join_time");
                    if (ids.getLength() > 0 && users.getLength() > 0) {
                        try {
                            String user = users.item(0).getTextContent().trim();
                            members.put(user, Integer.parseInt(ids.item(0).getTextContent()));
                            int join = 0;
                            if (joinTimes.getLength() > 0) {
                                try {
                                    join = Integer.parseInt(joinTimes.item(0).getTextContent().trim());
                                } catch (NumberFormatException ignored) {
                                }
                            }
                            joins.put(user, join);
                        } catch (NumberFormatException ignored) { }
                    }
                }
                memberMaps.put(name, members);
                joinTimeMaps.put(name, joins);
            }
            for (Room room : roomRepo.findAll()) {
                int count = counts.getOrDefault(normalizeConferenceName(room.getSipUri()), 0);
                if (room.getMemberCount() != count) {
                    room.setMemberCount(count);
                    roomRepo.save(room);
                }
            }
            synchronized (memberLock) {
                conferenceMemberMap.clear();
                conferenceMemberMap.putAll(memberMaps);
                conferenceJoinTimes.clear();
                conferenceJoinTimes.putAll(joinTimeMaps);
            }
            return true;
        } catch (Exception error) {
            log.warn("Unable to reconcile conference snapshot: {}", error.getMessage());
            return false;
        }
    }

    public String executeApi(String command) {
        if (!enabled) {
            return "-ERR ESL disabled";
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(3000);
            InputStream in = s.getInputStream();
            Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
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
                // ESL Content-Length counts UTF-8 bytes, not Java characters.
                // Chinese caller names otherwise leave this read waiting forever.
                byte[] body = in.readNBytes(len);
                if (body.length != len) return "-ERR incomplete ESL response";
                return new String(body, StandardCharsets.UTF_8).trim();
            }
            return headers.getOrDefault("Reply-Text", "+OK");
        } catch (Exception e) {
            log.warn("Failed to execute ESL api command '{}': {}", command, e.getMessage());
            return "-ERR " + e.getMessage();
        }
    }

    private Map<String, String> readMimeHeaders(InputStream input) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value != '\n') { line.write(value); continue; }
            String text = line.toString(StandardCharsets.UTF_8).trim();
            line.reset();
            if (text.isEmpty()) break;
            int colon = text.indexOf(':');
            if (colon > 0) headers.put(text.substring(0, colon).trim(), text.substring(colon + 1).trim());
        }
        return headers;
    }

    public Integer lookupMemberId(String confName, String sipId) {
        if (confName == null || confName.isBlank() || sipId == null || sipId.isBlank()) {
            return null;
        }
        String cleanSip = sipId.trim();
        java.util.Map<String, Integer> members;
        synchronized (memberLock) {
            members = conferenceMemberMap.get(confName);
            if (members != null && members.containsKey(cleanSip)) {
                return members.get(cleanSip);
            }
        }

        String listOut = executeApi("conference " + confName + " list");
        if (listOut != null && !listOut.startsWith("-ERR") && listOut.contains(";")) {
            for (String line : listOut.split("\\r?\\n")) {
                String[] parts = line.split(";");
                if (parts.length >= 5) {
                    try {
                        int mId = Integer.parseInt(parts[0].trim());
                        String callerNumber = parts[4].trim();
                        synchronized (memberLock) {
                            conferenceMemberMap.computeIfAbsent(confName, k -> new java.util.concurrent.ConcurrentHashMap<>())
                                    .put(callerNumber, mId);
                        }
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
