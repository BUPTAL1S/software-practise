package com.lab.sipim.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.lab.sipim.domain.Room;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.CdrRepository;
import com.lab.sipim.repo.RoomMessageRepository;
import com.lab.sipim.repo.RoomRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import com.lab.sipim.service.RoomPresenceRegistry;
import com.lab.sipim.service.RoomCallStatistics;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomRepository roomRepo;
    private final RoomMessageRepository messageRepo;
    private final UserRepository userRepo;
    private final LoginTokens tokens;
    private final RoomCallStatistics roomStatistics;
    private final RoomPresenceRegistry presence;
    @Autowired(required = false)
    private com.lab.sipim.esl.EslEventListener eslEventListener;

    public RoomController(
            RoomRepository roomRepo,
            RoomMessageRepository messageRepo,
            UserRepository userRepo,
            LoginTokens tokens,
            RoomCallStatistics roomStatistics,
            RoomPresenceRegistry presence) {
        this.roomRepo = roomRepo;
        this.messageRepo = messageRepo;
        this.userRepo = userRepo;
        this.tokens = tokens;
        this.roomStatistics = roomStatistics;
        this.presence = presence;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listRooms() {
        List<Room> rooms = roomRepo.findAllByOrderByIdAsc();
        List<Map<String, Object>> resp = new ArrayList<>();
        Map<String, Integer> peaks = rooms.isEmpty() ? Map.of() : roomStatistics.peaksByRoom();
        for (Room r : rooms) {
            resp.add(formatRoom(r, peaks.getOrDefault(RoomCallStatistics.roomKey(r.getSipUri()), 0)));
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{identifier}")
    public ResponseEntity<?> getRoom(@PathVariable("identifier") String identifier) {
        Optional<Room> found = findRoom(identifier);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "room not found"));
        }
        return ResponseEntity.ok(formatRoom(found.get()));
    }

    /**
     * Heartbeat used by a client while it is inside a conference. The ESL side
     * can distinguish a live participant from a killed client's bridged channel.
     */
    @PostMapping("/{identifier}/heartbeat")
    public ResponseEntity<?> heartbeat(
            @PathVariable("identifier") String identifier,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Optional<Room> found = findRoom(identifier);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "room not found"));
        }
        if (authorization == null || authorization.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", LoginController.UNAUTHORIZED));
        }
        String token = MeController.bearerToken(authorization);
        Optional<Long> uid = tokens.userIdOf(token);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", LoginController.UNAUTHORIZED));
        }
        Optional<User> user = userRepo.findById(uid.get());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", LoginController.UNAUTHORIZED));
        }
        presence.heartbeat(found.get().getSipUri(), user.get().getUsername());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/{identifier}/messages")
    public ResponseEntity<?> sendRoomMessage(
            @PathVariable("identifier") String identifier,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) SendRoomMessageRequest req) {
        Optional<Room> found = findRoom(identifier);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "room not found"));
        }
        Room r = found.get();
        String content = (req != null && req.getContent() != null) ? req.getContent().trim() : "";
        if (content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }

        if (authorization == null || authorization.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", LoginController.UNAUTHORIZED));
        }
        String token = MeController.bearerToken(authorization);
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", LoginController.UNAUTHORIZED));
        }
        Optional<Long> uid = tokens.userIdOf(token);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", LoginController.UNAUTHORIZED));
        }
        Optional<User> u = userRepo.findById(uid.get());
        if (u.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", LoginController.UNAUTHORIZED));
        }
        String finalSender = u.get().getUsername();

        // 防伪造校验：如果请求体也传入了 sender 且与登录用户身份不一致，直接拒绝 403 Forbidden
        String candidate = (req != null && req.getSender() != null) ? req.getSender().trim() : "";
        if (!candidate.isEmpty() && !candidate.equalsIgnoreCase(finalSender)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "cannot impersonate another user: " + candidate));
        }

        boolean isFile = req != null && Boolean.TRUE.equals(req.getIsFile());
        com.lab.sipim.domain.RoomMessage entity = new com.lab.sipim.domain.RoomMessage(
                r.getSipUri().toLowerCase(),
                finalSender,
                content,
                isFile
        );
        entity = messageRepo.save(entity);

        RoomMessage resp = new RoomMessage(
                entity.getId(),
                entity.getRoomUri(),
                entity.getSender(),
                entity.getContent(),
                entity.isFile(),
                entity.getCreatedAt()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/{identifier}/messages")
    public ResponseEntity<?> getRoomMessages(
            @PathVariable("identifier") String identifier,
            @RequestParam(value = "afterId", defaultValue = "0") long afterId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        Optional<Room> found = findRoom(identifier);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "room not found"));
        }
        Room r = found.get();
        String roomKey = r.getSipUri().toLowerCase();

        List<com.lab.sipim.domain.RoomMessage> entities;
        if (afterId > 0) {
            entities = messageRepo.findByRoomUriIgnoreCaseAndIdGreaterThanOrderByIdAsc(roomKey, afterId);
        } else {
            entities = messageRepo.findByRoomUriIgnoreCaseOrderByIdAsc(roomKey);
        }

        List<RoomMessage> result = new ArrayList<>();
        for (com.lab.sipim.domain.RoomMessage m : entities) {
            result.add(new RoomMessage(
                    m.getId(),
                    m.getRoomUri(),
                    m.getSender(),
                    m.getContent(),
                    m.isFile(),
                    m.getCreatedAt()
            ));
        }
        if (limit > 0 && result.size() > limit) {
            result = result.subList(result.size() - limit, result.size());
        }
        return ResponseEntity.ok(result);
    }

    private Optional<Room> findRoom(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        String trimmed = identifier.trim();
        try {
            long id = Long.parseLong(trimmed);
            Optional<Room> byId = roomRepo.findById(id);
            if (byId.isPresent()) return byId;
        } catch (NumberFormatException ignored) {}
        Optional<Room> byUri = roomRepo.findBySipUri(trimmed);
        if (byUri.isPresent()) return byUri;
        if (!trimmed.startsWith("room-")) {
            return roomRepo.findBySipUri("room-" + trimmed);
        }
        return Optional.empty();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createRoom(@RequestBody(required = false) CreateRoomRequest request) {
        String name = (request != null && request.getName() != null && !request.getName().isBlank())
                ? request.getName().trim()
                : "研讨室";

        int maxMembers = 8;
        if (request != null && request.getMaxMembers() != null) {
            int val = request.getMaxMembers();
            if (val != 8) {
                return ResponseEntity.badRequest().body(Map.of("error", "当前 MCU 会议配置固定支持最大 8 人，max_members 必须为 8 或缺省"));
            }
            maxMembers = val;
        }

        String customSipUri = (request != null && request.getSipUri() != null && !request.getSipUri().isBlank())
                ? request.getSipUri().trim()
                : null;

        if (customSipUri != null) {
            if (!customSipUri.matches("^room-[A-Za-z0-9_-]+$")) {
                return ResponseEntity.badRequest().body(Map.of("error", "自定 sip_uri 必须以 'room-' 开头并仅包含字母、数字、下划线或连字符"));
            }
            if (roomRepo.findBySipUri(customSipUri).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "room sip_uri already exists"));
            }
        }

        Room room = new Room();
        room.setName(name);
        room.setMaxMembers(maxMembers);
        room.setMemberCount(0);

        if (customSipUri != null) {
            room.setSipUri(customSipUri);
            Room saved = roomRepo.save(room);
            return ResponseEntity.status(HttpStatus.CREATED).body(formatRoom(saved));
        }

        // If no custom SIP URI, assign unique temporary placeholder to satisfy not-null constraint,
        // then update to standardized "room-<id>" once ID is assigned.
        room.setSipUri("temp-" + UUID.randomUUID());
        Room saved = roomRepo.saveAndFlush(room);
        saved.setSipUri("room-" + saved.getId());
        saved = roomRepo.save(saved);

        return ResponseEntity.status(HttpStatus.CREATED).body(formatRoom(saved));
    }

    @PostMapping("/{identifier}/vmute")
    public ResponseEntity<?> setRoomMemberVmute(
            @PathVariable("identifier") String identifier,
            @RequestParam("sipId") String sipId,
            @RequestParam(value = "mute", defaultValue = "true") boolean mute) {
        boolean ok = false;
        if (eslEventListener != null) {
            ok = eslEventListener.setMemberVmute(identifier, sipId, mute);
        }
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "room", identifier,
                "sipId", sipId,
                "muted", mute
        ));
    }

    private Map<String, Object> formatRoom(Room r) {
        return formatRoom(r, roomStatistics.peakForRoom(r.getSipUri()));
    }

    private Map<String, Object> formatRoom(Room r, int peak) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("sip_uri", r.getSipUri());
        m.put("sipUri", r.getSipUri());
        m.put("max_members", r.getMaxMembers());
        m.put("maxMembers", r.getMaxMembers());
        m.put("member_count", r.getMemberCount());
        m.put("memberCount", r.getMemberCount());

        m.put("peak_members", peak);
        m.put("peakMembers", peak);
        return m;
    }

    public static class CreateRoomRequest {
        private String name;

        @JsonAlias({"maxMembers", "max_members"})
        private Integer maxMembers;

        @JsonAlias({"sipUri", "sip_uri"})
        private String sipUri;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getMaxMembers() {
            return maxMembers;
        }

        public void setMaxMembers(Integer maxMembers) {
            this.maxMembers = maxMembers;
        }

        public String getSipUri() {
            return sipUri;
        }

        public void setSipUri(String sipUri) {
            this.sipUri = sipUri;
        }
    }

    public record RoomMessage(
            long id,
            String roomUri,
            String sender,
            String content,
            boolean isFile,
            Instant timestamp
    ) {}

    public static class SendRoomMessageRequest {
        private String sender;
        private String content;
        private Boolean isFile;

        public String getSender() {
            return sender;
        }

        public void setSender(String sender) {
            this.sender = sender;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Boolean getIsFile() {
            return isFile;
        }

        public void setIsFile(Boolean isFile) {
            this.isFile = isFile;
        }
    }
}

