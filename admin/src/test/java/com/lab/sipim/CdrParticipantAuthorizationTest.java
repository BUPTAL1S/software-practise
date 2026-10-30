package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.sipim.repo.CdrRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "SIPIM_DB=./target/test-cdr-participants.db",
        "freeswitch.esl.enabled=false",
        "sipim.avatar.fs-dir=target/cdr-participant-avatars",
        "sipim.avatar.upload-dir=target/cdr-participant-uploads"
})
class CdrParticipantAuthorizationTest {
    @Autowired MockMvc mvc;
    @Autowired CdrRepository cdrs;
    @Autowired UserRepository users;
    @Autowired LoginTokens tokens;
    @Autowired ObjectMapper json;

    @BeforeEach void reset() {
        cdrs.deleteAll();
        tokens.clear();
    }

    private String token(String username) {
        return tokens.issue(users.findByUsername(username).orElseThrow().getId());
    }

    private ResultActions report(String bearer, String callId, String caller, String callee, int seconds)
            throws Exception {
        var request = post("/api/cdr").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "call_id", callId, "caller", caller, "callee_or_room", callee,
                        "started_at", "2026-10-30T08:00:00Z", "duration_sec", seconds)));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return mvc.perform(request);
    }

    @Test void incomingParticipantCanReportWithoutCallerReport() throws Exception {
        report(token("1002"), "incoming-only", "1003", "1002", 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("1003"))
                .andExpect(jsonPath("$.callee_or_room").value("1002"));
        assertThat(cdrs.count()).isEqualTo(1);
    }

    @Test void bothParticipantsMergeOneCallAndKeepLargestDuration() throws Exception {
        report(token("1003"), "both-ends", "1003", "1002", 20).andExpect(status().isOk());
        report(token("1002"), "both-ends", "1003", "1002", 30).andExpect(status().isOk());
        report(token("1003"), "both-ends", "1003", "1002", 10).andExpect(status().isOk());
        assertThat(cdrs.count()).isEqualTo(1);
        var saved = cdrs.findByCallId("both-ends").get(0);
        assertThat(saved.getCaller()).isEqualTo("1003");
        assertThat(saved.getCalleeOrRoom()).isEqualTo("1002");
        assertThat(saved.getDurationSec()).isEqualTo(30);
    }

    @Test void unrelatedUserCannotReportSomeoneElsesCall() throws Exception {
        report(token("1004"), "unrelated", "1003", "1002", 20).andExpect(status().isForbidden());
        assertThat(cdrs.count()).isZero();
    }

    @Test void callerCannotReuseAnotherCallsIdToOverwriteItsDuration() throws Exception {
        report(token("1003"), "existing-call", "1003", "1002", 20).andExpect(status().isOk());
        report(token("1004"), "existing-call", "1004", "1005", 999).andExpect(status().isForbidden());
        var saved = cdrs.findByCallId("existing-call").get(0);
        assertThat(saved.getCaller()).isEqualTo("1003");
        assertThat(saved.getCalleeOrRoom()).isEqualTo("1002");
        assertThat(saved.getDurationSec()).isEqualTo(20);
    }

    @Test void calleeCannotReuseAnotherCallsIdToOverwriteItsDuration() throws Exception {
        report(token("1003"), "existing-inbound", "1003", "1002", 20).andExpect(status().isOk());
        report(token("1004"), "existing-inbound", "1005", "1004", 999).andExpect(status().isForbidden());
        assertThat(cdrs.findByCallId("existing-inbound").get(0).getDurationSec()).isEqualTo(20);
    }

    @Test void missingAndExpiredSessionsAreRejected() throws Exception {
        report(null, "no-session", "1003", "1002", 20).andExpect(status().isUnauthorized());
        String expired = token("1002");
        tokens.expireAllForTesting();
        report(expired, "expired", "1003", "1002", 20).andExpect(status().isUnauthorized());
        assertThat(cdrs.count()).isZero();
    }

    @Test void roomCallerCanReportItsOwnConferenceLeg() throws Exception {
        report(token("1002"), "room-leg", "1002", "room-101", 20)
                .andExpect(status().isOk()).andExpect(jsonPath("$.callee_or_room").value("room-101"));
    }

    @Test void fullSipAddressStillIdentifiesTheCallee() throws Exception {
        report(token("1002"), "sip-identities", "sip:1003@lab", "sip:1002@lab", 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller").value("sip:1003@lab"));
    }

    @Test void administratorMayStillReportOnBehalfOfParticipants() throws Exception {
        report(token("1001"), "admin-report", "1003", "1002", 20).andExpect(status().isOk());
    }
}
