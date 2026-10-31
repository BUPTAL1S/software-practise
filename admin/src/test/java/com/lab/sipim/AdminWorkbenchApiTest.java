package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "SIPIM_DB=./target/test-workbench.db", "freeswitch.esl.enabled=false",
    "sipim.avatar.fs-dir=target/workbench-avatars", "sipim.avatar.upload-dir=target/workbench-uploads"
})
class AdminWorkbenchApiTest {
    static {
        try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/test-workbench.db")); }
        catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired LoginTokens tokens;
    @Autowired ObjectMapper json;
    @BeforeEach void clearSessions() { tokens.clear(); }

    String token(String name) { return tokens.issue(users.findByUsername(name).orElseThrow().getId()); }

    @Test void allWorkbenchRoutesAndLegacyManagementAliasesEnforceAdmin() throws Exception {
        String regular = token("1002"), admin = token("1001");
        for (String path : new String[] { "/api/admin/session", "/api/admin/workbench/health",
                "/api/admin/workbench/stats", "/api/admin/workbench/cdr", "/api/admin/workbench/rooms",
                "/api/admin/workbench/users", "/api/admin/workbench/users/online", "/api/admin/workbench/files",
                "/api/admin/messages", "/api/admin/room-messages", "/api/files", "/api/files;test=1",
                "/api/users/online", "/api/cdr/999999" }) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer " + regular)).andExpect(status().isForbidden());
        }
        for (String path : new String[] { "/api/admin/workbench/health", "/api/admin/workbench/stats",
                "/api/admin/workbench/cdr", "/api/admin/workbench/rooms", "/api/admin/workbench/users",
                "/api/admin/workbench/users/online", "/api/admin/workbench/files" }) {
            mvc.perform(get(path).header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(post("/api/admin/workbench/rooms").header("Authorization", "Bearer " + regular)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"unauthorized\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + regular)
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"forbidden\",\"password\":\"secret\"}"))
                .andExpect(status().isForbidden());
        assertThat(users.findByUsername("forbidden")).isEmpty();
    }

    @Test void browserLoginRejectsOrdinaryUsersEvenIfTheirRoleIsChanged() throws Exception {
        mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"1002\",\"password\":\"1002\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.token").doesNotExist());
        var regular = users.findByUsername("1002").orElseThrow();
        regular.setRole("ADMIN"); users.saveAndFlush(regular);
        try {
            mvc.perform(get("/api/admin/workbench/users").header("Authorization", "Bearer " + token("1002")))
                    .andExpect(status().isForbidden());
        } finally { regular.setRole("USER"); users.saveAndFlush(regular); }
        mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"1001\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void logoutRevokesOnlyBrowserSessionAndExpiredTokensAreRejected() throws Exception {
        String desktop = token("1001");
        String response = mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"1001\",\"password\":\"1001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();
        String browser = json.readTree(response).get("token").asText();
        mvc.perform(post("/api/admin/logout").header("Authorization", "Bearer " + browser)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/session").header("Authorization", "Bearer " + browser)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + desktop)).andExpect(status().isOk());
        tokens.expireAllForTesting();
        mvc.perform(get("/api/admin/workbench/stats").header("Authorization", "Bearer " + desktop))
                .andExpect(status().isUnauthorized());
    }

    @Test void clientDirectorySignupAndAuthenticatedRoomOperationsRemainAvailable() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isOk()).andExpect(jsonPath("$..passwordHash").doesNotExist());
        mvc.perform(get("/api/rooms")).andExpect(status().isOk());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"workbench-client\",\"password\":\"client-secret\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").isNotEmpty());
        String regular = token("1002");
        mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"room\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/rooms").header("Authorization", "Bearer " + regular)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"client room\",\"max_members\":8}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/rooms/room-101/heartbeat").header("Authorization", "Bearer " + regular)).andExpect(status().isOk());
        mvc.perform(post("/api/rooms/room-101/vmute?sipId=1001&mute=true").header("Authorization", "Bearer " + regular))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/cdr?caller=1002").header("Authorization", "Bearer " + regular)).andExpect(status().isOk());
        mvc.perform(get("/api/cdr?caller=1001").header("Authorization", "Bearer " + regular)).andExpect(status().isForbidden());
        mvc.perform(get("/api/cdr/stats").header("Authorization", "Bearer " + regular)).andExpect(status().isOk());
        mvc.perform(post("/api/messages").header("Authorization", "Bearer " + regular).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("receiver", "1001", "content", "client compatibility"))))
                .andExpect(status().isCreated());
    }

    @Test void privateDownloadsRequireOwnerRecipientOrAdmin() throws Exception {
        var result = mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "test.txt", "text/plain", new byte[]{1}))
                .param("from", "1002").param("to", "1003").header("Authorization", "Bearer " + token("1002")))
                .andExpect(status().isOk()).andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/files/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(head("/api/files/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/files/" + id).header("Authorization", "Bearer " + token("1004"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/files/" + id).header("Authorization", "Bearer " + token("1003"))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/workbench/files/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/workbench/files/" + id).header("Authorization", "Bearer " + token("1003")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/workbench/files/" + id).header("Authorization", "Bearer " + token("1001")))
                .andExpect(status().isOk());
    }

    @Test void authenticatedClientCannotImpersonateAnotherUploaderOrCaller() throws Exception {
        String regular = token("1002");
        mvc.perform(multipart("/api/files").file(new MockMultipartFile("file", "test.txt", "text/plain", new byte[]{1}))
                .param("from", "1003").header("Authorization", "Bearer " + regular)).andExpect(status().isForbidden());
        mvc.perform(post("/api/cdr").header("Authorization", "Bearer " + regular).contentType(MediaType.APPLICATION_JSON)
                .content("{\"caller\":\"1003\",\"callee_or_room\":\"1004\",\"duration_sec\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test void onlyPublishedProfileAvatarIsPublic() throws Exception {
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
        var user = users.findByUsername("1002").orElseThrow();
        String url = mvc.getDispatcherServlet().getWebApplicationContext()
            .getBean(com.lab.sipim.service.AvatarService.class).saveUploadedAvatar(user,
                new MockMultipartFile("file", "avatar.png", "image/png", png.toByteArray()));
        mvc.perform(get(url)).andExpect(status().isOk());
        mvc.perform(head(url)).andExpect(status().isOk());
    }

    @Test void customExtensionAndFullSipRecipientsStillWork() throws Exception {
        var user = users.findByUsername("1003").orElseThrow();
        String originalSip = user.getSipId();
        user.setSipId("7303"); users.saveAndFlush(user);
        try {
            String regular = token("1003");
            for (var attempt : new Map[]{Map.of("username", "7303", "password", "secret", "sipId", "new-extension"),
                    Map.of("username", "new-identity", "password", "secret", "sipId", "1003")}) {
                mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(attempt)))
                        .andExpect(status().isConflict());
            }
            var fake = new com.lab.sipim.domain.User(); fake.setUsername("sip:7303@lab"); fake.setSipId("another-extension");
            assertThat(com.lab.sipim.security.ApiAccessPolicy.isIdentity(fake, "sip:7303@lab")).isFalse();
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            var gate = new java.util.concurrent.CountDownLatch(1);
            try {
                var left = pool.submit(() -> {
                    gate.await();
                    return mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"alias-left\",\"sipId\":\"alias-right\",\"password\":\"secret\"}"))
                            .andReturn().getResponse().getStatus();
                });
                var right = pool.submit(() -> {
                    gate.await();
                    return mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"alias-right\",\"sipId\":\"alias-left\",\"password\":\"secret\"}"))
                            .andReturn().getResponse().getStatus();
                });
                gate.countDown();
                assertThat(java.util.List.of(left.get(5, java.util.concurrent.TimeUnit.SECONDS),
                        right.get(5, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
            } finally { pool.shutdownNow(); }
            mvc.perform(get("/api/cdr?caller=7303").header("Authorization", "Bearer " + regular))
                    .andExpect(status().isOk());
            for (String recipient : new String[]{"sip:7303@lab", "7303", "sip:room-101@lab"}) {
                var result = mvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "sip-address.txt", "text/plain", new byte[]{1}))
                        .param("from", "1002").param("to", recipient)
                        .header("Authorization", "Bearer " + token("1002"))).andExpect(status().isOk()).andReturn();
                long id = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
                mvc.perform(get("/api/files/" + id).header("Authorization", "Bearer " + regular)).andExpect(status().isOk());
            }
        } finally { user.setSipId(originalSip); users.saveAndFlush(user); }
    }
}
