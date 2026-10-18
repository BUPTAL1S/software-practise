package com.lab.sipim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.sipim.domain.Room;
import com.lab.sipim.repo.CdrRepository;
import com.lab.sipim.repo.RoomRepository;
import com.lab.sipim.security.LoginTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class CdrApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CdrRepository cdrRepo;

    @Autowired
    private RoomRepository roomRepo;

    @Autowired
    private LoginTokens tokens;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void cleanUp() {
        cdrRepo.deleteAll();
        for (Room r : roomRepo.findAll()) {
            r.setMemberCount(0);
            roomRepo.save(r);
        }
    }

    @Test
    void reportCdrSuccess() throws Exception {
        Map<String, Object> req = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T10:00:00Z",
                "duration_sec", 45
        );

        mvc.perform(post("/api/cdr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.caller").value("1001"))
                .andExpect(jsonPath("$.callee_or_room").value("1002"))
                .andExpect(jsonPath("$.duration_sec").value(45));
    }

    @Test
    void reportCdrMissingFieldsReturns400() throws Exception {
        Map<String, Object> req = Map.of("duration_sec", 10);

        mvc.perform(post("/api/cdr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("caller and callee_or_room are required"));
    }

    @Test
    void reportCdrNegativeDurationReturns400() throws Exception {
        Map<String, Object> req = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "duration_sec", -5
        );

        mvc.perform(post("/api/cdr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("duration_sec must be non-negative"));
    }

    @Test
    void reportCdrWithValidToken() throws Exception {
        String token = tokens.issue(1L);

        Map<String, Object> req = Map.of(
                "callee_or_room", "1002",
                "duration_sec", 30
        );

        mvc.perform(post("/api/cdr")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("1001"))
                .andExpect(jsonPath("$.callee_or_room").value("1002"))
                .andExpect(jsonPath("$.duration_sec").value(30));
    }

    @Test
    void reportCdrWithBadTokenReturns401() throws Exception {
        Map<String, Object> req = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "duration_sec", 20
        );

        mvc.perform(post("/api/cdr")
                        .header("Authorization", "Bearer bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportCdrDeduplicationWorks() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> req1 = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", now.toString(),
                "duration_sec", 20
        );
        Map<String, Object> req2 = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", now.plusSeconds(1).toString(),
                "duration_sec", 21
        );

        mvc.perform(post("/api/cdr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duration_sec").value(20));

        mvc.perform(post("/api/cdr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duration_sec").value(21));

        // Total records in DB must still be 1!
        org.junit.jupiter.api.Assertions.assertEquals(1, cdrRepo.count());
    }

    @Test
    void listCdrAndStats() throws Exception {
        Map<String, Object> c1 = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T11:00:00Z",
                "duration_sec", 30
        );
        Map<String, Object> c2 = Map.of(
                "caller", "1002",
                "callee_or_room", "room-101",
                "started_at", "2026-10-13T12:00:00Z",
                "duration_sec", 90
        );

        mvc.perform(post("/api/cdr")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(c1))).andExpect(status().isOk());

        mvc.perform(post("/api/cdr")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(c2))).andExpect(status().isOk());

        // List
        mvc.perform(get("/api/cdr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].callee_or_room").value("room-101"))
                .andExpect(jsonPath("$[1].callee_or_room").value("1002"));

        // Stats
        mvc.perform(get("/api/cdr/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(2))
                .andExpect(jsonPath("$.totalDurationSec").value(120))
                .andExpect(jsonPath("$.averageDurationSec").value(60.0))
                .andExpect(jsonPath("$.peakRoomMembers").value(1));
    }

    @Test
    void reportCdrConsecutiveCallsWithin15SecondsNotMerged() throws Exception {
        // Call 1: 1001 -> 1002, starts at 14:00:00, duration 2s (ends at 14:00:02)
        Map<String, Object> req1 = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T14:00:00Z",
                "duration_sec", 2
        );

        // Call 2: 1001 -> 1002, starts 5s later at 14:00:05, duration 10s
        Map<String, Object> req2 = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T14:00:05Z",
                "duration_sec", 10
        );

        mvc.perform(post("/api/cdr")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req1)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/cdr")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req2)))
                .andExpect(status().isOk());

        // Consecutive calls must be preserved as 2 separate records!
        org.junit.jupiter.api.Assertions.assertEquals(2, cdrRepo.count());
    }

    @Test
    void reportCdrCallbackFromBtoANotMergedWithAtoB() throws Exception {
        // A calls B
        Map<String, Object> req1 = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T14:00:00Z",
                "duration_sec", 10
        );

        // B calls A (callback)
        Map<String, Object> req2 = Map.of(
                "caller", "1002",
                "callee_or_room", "1001",
                "started_at", "2026-10-13T14:00:05Z",
                "duration_sec", 8
        );

        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(req1))).andExpect(status().isOk());
        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(req2))).andExpect(status().isOk());

        // A->B and B->A are distinct calls and must not be merged
        org.junit.jupiter.api.Assertions.assertEquals(2, cdrRepo.count());
    }

    @Test
    void reportCdrSameCallByBothEndsWithCallIdDeduplicated() throws Exception {
        String callId = "sip-call-xyz-12345";
        Map<String, Object> endA = Map.of(
                "call_id", callId,
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T14:00:00Z",
                "duration_sec", 40
        );
        Map<String, Object> endB = Map.of(
                "call_id", callId,
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T14:00:01Z",
                "duration_sec", 42
        );

        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(endA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duration_sec").value(40));

        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(endB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duration_sec").value(42));

        // Merged to 1 record with duration 42
        org.junit.jupiter.api.Assertions.assertEquals(1, cdrRepo.count());
    }

    @Test
    void roomPeakMembersIsConcurrentNotHistoricalUniqueAccounts() throws Exception {
        // room-101 has 3 simultaneous overlapping participants (15:00:20 is overlap)
        Map<String, Object> call1 = Map.of(
                "caller", "1001",
                "callee_or_room", "room-101",
                "started_at", "2026-10-13T15:00:00Z",
                "duration_sec", 60
        );
        Map<String, Object> call2 = Map.of(
                "caller", "1002",
                "callee_or_room", "room-101",
                "started_at", "2026-10-13T15:00:10Z",
                "duration_sec", 50
        );
        Map<String, Object> call3 = Map.of(
                "caller", "1003",
                "callee_or_room", "room-101",
                "started_at", "2026-10-13T15:00:20Z",
                "duration_sec", 40
        );

        // room-102 has 3 non-overlapping sequential calls across history
        Map<String, Object> seq1 = Map.of(
                "caller", "1001",
                "callee_or_room", "room-102",
                "started_at", "2026-10-13T16:00:00Z",
                "duration_sec", 10
        );
        Map<String, Object> seq2 = Map.of(
                "caller", "1002",
                "callee_or_room", "room-102",
                "started_at", "2026-10-13T16:05:00Z",
                "duration_sec", 10
        );
        Map<String, Object> seq3 = Map.of(
                "caller", "1003",
                "callee_or_room", "room-102",
                "started_at", "2026-10-13T16:10:00Z",
                "duration_sec", 10
        );

        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(call1))).andExpect(status().isOk());
        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(call2))).andExpect(status().isOk());
        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(call3))).andExpect(status().isOk());

        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(seq1))).andExpect(status().isOk());
        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(seq2))).andExpect(status().isOk());
        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(seq3))).andExpect(status().isOk());

        // room-101 has peak concurrent = 3
        // room-102 has peak concurrent = 1 (even though 3 distinct callers called historically)
        // Check that room memberCount in DB is NOT overwritten by CDR (reserved solely for ESL)
        Room r102 = roomRepo.findBySipUri("room-102").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(0, r102.getMemberCount());

        Room r101 = roomRepo.findBySipUri("room-101").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(0, r101.getMemberCount());

        // Check that room-101 format returns dynamic peak_members=3 and room-102 returns peak_members=1
        mvc.perform(get("/api/rooms/room-101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member_count").value(0))
                .andExpect(jsonPath("$.peak_members").value(3));

        mvc.perform(get("/api/rooms/room-102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member_count").value(0))
                .andExpect(jsonPath("$.peak_members").value(1));

        // Overall peak across rooms is 3 (from room-101)
        mvc.perform(get("/api/cdr/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peakRoomMembers").value(3));
    }

    @Test
    void consecutiveCallStartingAfterZeroDurationCallNotMerged() throws Exception {
        // Call 1: 1001 -> 1002, starts at 10:00:00, duration 0s (cancelled/unanswered)
        Map<String, Object> req1 = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T10:00:00Z",
                "duration_sec", 0
        );

        // Call 2: 1001 -> 1002, redials 1s later at 10:00:01, duration 8s
        Map<String, Object> req2 = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T10:00:01Z",
                "duration_sec", 8
        );

        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(req1))).andExpect(status().isOk());
        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(req2))).andExpect(status().isOk());

        // Both calls must be preserved as 2 separate records
        org.junit.jupiter.api.Assertions.assertEquals(2, cdrRepo.count());
    }

    @Test
    void handoffAtSameSecondCountsBoundaryOverlap() throws Exception {
        // Call 1: in room-101 from 15:00:00 to 15:00:10 (10s duration)
        Map<String, Object> call1 = Map.of(
                "caller", "1001",
                "callee_or_room", "room-101",
                "started_at", "2026-10-13T15:00:00Z",
                "duration_sec", 10
        );

        // Call 2: in room-101 from 15:00:10 to 15:00:20 (starts at exact second call1 ends)
        Map<String, Object> call2 = Map.of(
                "caller", "1002",
                "callee_or_room", "room-101",
                "started_at", "2026-10-13T15:00:10Z",
                "duration_sec", 10
        );

        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(call1))).andExpect(status().isOk());
        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(call2))).andExpect(status().isOk());

        // Both are saved
        org.junit.jupiter.api.Assertions.assertEquals(2, cdrRepo.count());

        // At 15:00:10, enter (+1) is sorted before leave (-1), so simultaneous boundary overlap is 2
        Room r101 = roomRepo.findBySipUri("room-101").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(0, r101.getMemberCount());

        mvc.perform(get("/api/rooms/room-101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member_count").value(0))
                .andExpect(jsonPath("$.peak_members").value(2));

        mvc.perform(get("/api/cdr/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peakRoomMembers").value(2));
    }

    @Test
    void sameSecondCounterpartReportUpdatesZeroDurationCdr() throws Exception {
        // One side reports 0s at 11:00:00
        Map<String, Object> req1 = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T11:00:00Z",
                "duration_sec", 0
        );
        // Other side reports 12s at identical second 11:00:00
        Map<String, Object> req2 = Map.of(
                "caller", "1001",
                "callee_or_room", "1002",
                "started_at", "2026-10-13T11:00:00Z",
                "duration_sec", 12
        );

        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(req1))).andExpect(status().isOk());
        mvc.perform(post("/api/cdr").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(req2))).andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(1, cdrRepo.count());
        org.junit.jupiter.api.Assertions.assertEquals(12, cdrRepo.findAll().get(0).getDurationSec());
    }
}
