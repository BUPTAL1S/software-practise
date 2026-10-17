package com.lab.sipim.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.lab.sipim.domain.Room;
import com.lab.sipim.repo.CdrRepository;
import com.lab.sipim.repo.RoomRepository;
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
    private final CdrRepository cdrRepo;

    public RoomController(RoomRepository roomRepo, @Autowired(required = false) CdrRepository cdrRepo) {
        this.roomRepo = roomRepo;
        this.cdrRepo = cdrRepo;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listRooms() {
        List<Room> rooms = roomRepo.findAllByOrderByIdAsc();
        List<Map<String, Object>> resp = new ArrayList<>();
        for (Room r : rooms) {
            resp.add(formatRoom(r));
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{identifier}")
    public ResponseEntity<?> getRoom(@PathVariable("identifier") String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        String trimmed = identifier.trim();
        Optional<Room> found = Optional.empty();

        // Try numeric ID lookup
        try {
            long id = Long.parseLong(trimmed);
            found = roomRepo.findById(id);
        } catch (NumberFormatException ignored) {
            // Not a numeric ID, search by SIP URI below
        }

        if (found.isEmpty()) {
            found = roomRepo.findBySipUri(trimmed);
        }
        if (found.isEmpty() && !trimmed.startsWith("room-")) {
            found = roomRepo.findBySipUri("room-" + trimmed);
        }

        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "room not found"));
        }
        return ResponseEntity.ok(formatRoom(found.get()));
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

    private Map<String, Object> formatRoom(Room r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("sip_uri", r.getSipUri());
        m.put("sipUri", r.getSipUri());
        m.put("max_members", r.getMaxMembers());
        m.put("maxMembers", r.getMaxMembers());
        m.put("member_count", r.getMemberCount());
        m.put("memberCount", r.getMemberCount());

        int peak = (cdrRepo != null && r.getSipUri() != null)
                ? CdrController.calculatePeakConcurrentMembersForRoom(cdrRepo, r.getSipUri())
                : 0;
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
}
