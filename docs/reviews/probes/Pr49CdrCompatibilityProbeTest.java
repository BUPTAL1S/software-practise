package com.lab.sipim;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "SIPIM_DB=./target/pr49-review-cdr.db", "freeswitch.esl.enabled=false",
    "sipim.avatar.fs-dir=target/review-avatars", "sipim.avatar.upload-dir=target/review-avatar-uploads"
})
class Pr49CdrCompatibilityProbeTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired LoginTokens tokens;

    private void reportAs(String reporter, String callId) throws Exception {
        String token = tokens.issue(users.findByUsername(reporter).orElseThrow().getId());
        mvc.perform(post("/api/cdr").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"call_id\":\"" + callId + "\",\"caller\":\"1003\",\"callee_or_room\":\"1002\",\"started_at\":\"2026-10-30T08:00:00Z\",\"duration_sec\":20}"))
                .andExpect(status().isOk());
    }

    @Test void outgoingParticipantCanReport() throws Exception {
        reportAs("1003", "review-outgoing");
    }

    @Test void incomingParticipantCanReport() throws Exception {
        reportAs("1002", "review-incoming");
    }
}