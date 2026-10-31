package com.lab.sipim.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lab.sipim.domain.Cdr;
import com.lab.sipim.domain.Room;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.CdrRepository;
import com.lab.sipim.repo.RoomRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.ApiAccessPolicy;
import com.lab.sipim.security.LoginTokens;
import com.lab.sipim.service.RoomCallStatistics;
import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CdrController {

    private final CdrRepository cdrRepo;
    private final RoomRepository roomRepo;
    private final UserRepository userRepo;
    private final LoginTokens tokens;
    private final RoomCallStatistics roomStatistics;

    public CdrController(
            CdrRepository cdrRepo,
            RoomRepository roomRepo,
            UserRepository userRepo,
            LoginTokens tokens, RoomCallStatistics roomStatistics) {
        this.cdrRepo = cdrRepo;
        this.roomRepo = roomRepo;
        this.userRepo = userRepo;
        this.tokens = tokens;
        this.roomStatistics = roomStatistics;
    }

    @PostMapping("/cdr")
    public ResponseEntity<?> reportCdr(
            @RequestBody(required = false) CdrReportRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "caller and callee_or_room are required"));
        }

        String caller = request.getCaller();
        User reporter = null;
        if (authorization != null && !authorization.isBlank()) {
            String token = MeController.bearerToken(authorization);
            if (token == null) {
                return ResponseEntity.status(401).body(Map.of("error", LoginController.UNAUTHORIZED));
            }
            Optional<Long> uid = tokens.userIdOf(token);
            if (uid.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of("error", LoginController.UNAUTHORIZED));
            }
            Optional<User> u = userRepo.findById(uid.get());
            if (u.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of("error", LoginController.UNAUTHORIZED));
            }
            reporter = u.get();
            if (caller == null || caller.isBlank()) {
                caller = reporter.getUsername();
            }
        }

        String calleeOrRoom = request.getCalleeOrRoom();
        if (caller == null || caller.isBlank() || calleeOrRoom == null || calleeOrRoom.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "caller and callee_or_room are required"));
        }

        if (request.getDurationSec() == null || request.getDurationSec() < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "duration_sec must be non-negative"));
        }

        if (reporter != null && !canReportCdr(reporter, caller, calleeOrRoom)) {
            return ResponseEntity.status(403).body(Map.of("error", "只能上报自己参与的通话记录"));
        }

        Instant startedAt = request.getStartedAt();
        if (startedAt == null) {
            startedAt = Instant.now();
        }

        String trimmedCallId = (request.getCallId() != null && !request.getCallId().isBlank())
                ? request.getCallId().trim()
                : null;
        String trimmedCaller = caller.trim();
        String trimmedCallee = calleeOrRoom.trim();
        int durationSec = request.getDurationSec();

        // 1. Deduplication by explicit SIP call_id if provided (100% exact match for the same dialog)
        if (trimmedCallId != null) {
            List<Cdr> byCallId = cdrRepo.findByCallId(trimmedCallId);
            if (!byCallId.isEmpty()) {
                Cdr existing = byCallId.get(0);
                // The submitted participants cannot grant access to someone else's Call-ID.
                if (reporter != null && !canReportCdr(reporter, existing.getCaller(), existing.getCalleeOrRoom())) {
                    return ResponseEntity.status(403).body(Map.of("error", "不能修改其他用户的通话记录"));
                }
                if (durationSec > existing.getDurationSec()) {
                    existing.setDurationSec(durationSec);
                    existing = cdrRepo.save(existing);
                }
                return ResponseEntity.ok(formatCdr(existing));
            }
        }

        // 2. Fallback deduplication by exact caller, callee, and start time window.
        // Only merge if started within ±3s of each other AND the new report did not start
        // after the previous call was already completed (so consecutive short calls are preserved).
        List<Cdr> duplicates = cdrRepo.findDuplicates(
                trimmedCaller, trimmedCallee, startedAt.minusSeconds(3), startedAt.plusSeconds(3));
        for (Cdr existing : duplicates) {
            Instant existingEnd = existing.getStartedAt().plusSeconds(existing.getDurationSec());
            boolean isSubsequentCall;
            if (existing.getDurationSec() > 0) {
                // A new call that started at or after the previous call ended (startedAt >= existingEnd) is distinct
                isSubsequentCall = !startedAt.isBefore(existingEnd);
            } else {
                // A 0-second call ended at its start time; any call starting strictly after it is distinct
                isSubsequentCall = startedAt.isAfter(existing.getStartedAt());
            }
            if (!isSubsequentCall) {
                if (durationSec > existing.getDurationSec()) {
                    existing.setDurationSec(durationSec);
                    existing = cdrRepo.save(existing);
                }
                return ResponseEntity.ok(formatCdr(existing));
            }
        }

        Cdr cdr = new Cdr();
        if (trimmedCallId != null) {
            cdr.setCallId(trimmedCallId);
        }
        cdr.setCaller(trimmedCaller);
        cdr.setCalleeOrRoom(trimmedCallee);
        cdr.setStartedAt(startedAt);
        cdr.setDurationSec(durationSec);
        Cdr saved = cdrRepo.save(cdr);
        return ResponseEntity.ok(formatCdr(saved));
    }

    private static boolean canReportCdr(User user, String caller, String calleeOrRoom) {
        return ApiAccessPolicy.isAdmin(user)
                || ApiAccessPolicy.isIdentity(user, caller)
                || ApiAccessPolicy.isIdentity(user, calleeOrRoom);
    }

    @GetMapping("/cdr")
    public ResponseEntity<List<Map<String, Object>>> listCdr(
            @RequestParam(value = "caller", required = false) String caller,
            @RequestParam(value = "callee", required = false) String callee,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {

        List<Cdr> list;
        if (caller != null && !caller.isBlank()) {
            list = cdrRepo.findByCallerOrderByIdDesc(caller.trim());
        } else if (callee != null && !callee.isBlank()) {
            list = cdrRepo.findByCalleeOrRoomOrderByIdDesc(callee.trim());
        } else {
            list = cdrRepo.findAllByOrderByIdDesc();
        }

        if (limit > 0 && list.size() > limit) {
            list = list.subList(0, limit);
        }

        List<Map<String, Object>> resp = new ArrayList<>();
        for (Cdr c : list) {
            resp.add(formatCdr(c));
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/cdr/{id}")
    public ResponseEntity<?> getCdrById(@PathVariable("id") Long id) {
        Optional<Cdr> found = cdrRepo.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(formatCdr(found.get()));
    }

    @GetMapping({"/cdr/stats", "/stats"})
    public ResponseEntity<Map<String, Object>> getStats() {
        long totalCalls = cdrRepo.count();
        long totalDurationSec = cdrRepo.sumTotalDurationSec();
        double avgDurationSec = totalCalls == 0
                ? 0.0
                : Math.round(((double) totalDurationSec / totalCalls) * 10.0) / 10.0;

        // Peak simultaneous room members across all rooms (concurrent overlap, not distinct historical accounts)
        int peakRoomMembers = roomRepo.findPeakMemberCount();
        for (int concurrentPeak : roomStatistics.peaksByRoom().values()) {
            peakRoomMembers = Math.max(peakRoomMembers, concurrentPeak);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCalls", totalCalls);
        stats.put("totalDurationSec", totalDurationSec);
        stats.put("averageDurationSec", avgDurationSec);
        stats.put("peakRoomMembers", peakRoomMembers);

        return ResponseEntity.ok(stats);
    }

    static String normalizeRoomUri(String target) {
        String key = RoomCallStatistics.roomKey(target);
        return key.isEmpty() ? null : key;
    }

    public static int calculatePeakConcurrentMembersForRoom(CdrRepository records, String room) {
        if (records == null || room == null || room.isBlank()) return 0;
        return new RoomCallStatistics(records).peakForRoom(room);
    }
    private static Map<String, Object> formatCdr(Cdr c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        if (c.getCallId() != null) {
            map.put("call_id", c.getCallId());
            map.put("callId", c.getCallId());
        }
        map.put("caller", c.getCaller());
        map.put("callee_or_room", c.getCalleeOrRoom());
        map.put("calleeOrRoom", c.getCalleeOrRoom());
        map.put("started_at", c.getStartedAt().toString());
        map.put("startedAt", c.getStartedAt().toString());
        map.put("duration_sec", c.getDurationSec());
        map.put("durationSec", c.getDurationSec());
        return map;
    }

    public static class CdrReportRequest {
        @JsonAlias({"callId", "call_id"})
        private String callId;

        private String caller;

        @JsonAlias({"calleeOrRoom", "callee_or_room"})
        private String calleeOrRoom;

        @JsonAlias({"startedAt", "started_at"})
        private Instant startedAt;

        @JsonAlias({"durationSec", "duration_sec"})
        private Integer durationSec;

        public String getCallId() {
            return callId;
        }

        public void setCallId(String callId) {
            this.callId = callId;
        }

        public String getCaller() {
            return caller;
        }

        public void setCaller(String caller) {
            this.caller = caller;
        }

        public String getCalleeOrRoom() {
            return calleeOrRoom;
        }

        public void setCalleeOrRoom(String calleeOrRoom) {
            this.calleeOrRoom = calleeOrRoom;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(Instant startedAt) {
            this.startedAt = startedAt;
        }

        public Integer getDurationSec() {
            return durationSec;
        }

        public void setDurationSec(Integer durationSec) {
            this.durationSec = durationSec;
        }
    }
}
