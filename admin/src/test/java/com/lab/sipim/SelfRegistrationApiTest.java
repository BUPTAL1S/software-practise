package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import com.lab.sipim.service.IdentityReservations;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"freeswitch.esl.enabled=false",
        "sipim.avatar.fs-dir=./target/self-registration/avatars",
        "sipim.avatar.upload-dir=./target/self-registration/uploads"})
@AutoConfigureMockMvc
class SelfRegistrationApiTest {
    private static final String DATABASE = "jdbc:sqlite:./target/self-registration-" + UUID.randomUUID() + ".db";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired LoginTokens tokens;
    @Autowired IdentityReservations identities;
    @Autowired PasswordEncoder passwords;

    @Test void bothRegistrationEntriesRequireAtLeastFourCharacters() throws Exception {
        String admin = tokens.issue(users.findByUsername("1001").orElseThrow().getId());
        for (String endpoint : List.of("/api/register", "/api/admin/workbench/users")) {
            String username = endpoint.equals("/api/register") ? "7455" : "7456";
            for (String shortPassword : List.of("1", "123", "🔒🔒")) {
                mvc.perform(post(endpoint).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", shortPassword))))
                        .andExpect(status().isBadRequest());
                assertThat(users.findByUsername(username)).isEmpty();
            }
            mvc.perform(post(endpoint).header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", username, "password", "a234"))))
                    .andExpect(status().isCreated());
        }
    }

    @Test void newAccountPreservesLeadingZeroAndChosenPasswordAndAppearsInAdmin() throws Exception {
        String password = " 自定\"密\\码\t ";
        String request = json.writeValueAsString(Map.of("username", "0427", "password", password, "role", "ADMIN"));
        var response = mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value("0427"))
                .andExpect(jsonPath("$.sipId").value("0427")).andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse();
        String token = json.readTree(response.getContentAsString()).get("token").asText();
        var saved = users.findByUsername("0427").orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo(password);
        assertThat(passwords.matches(password, saved.getPasswordHash())).isTrue();
        assertThat(saved.getRole()).isEqualTo("USER");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("0427"));
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk());
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"0427\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users")).andExpect(jsonPath("$[?(@.username == '0427')]").exists());
        String admin = tokens.issue(users.findByUsername("1001").orElseThrow().getId());
        mvc.perform(get("/api/admin/workbench/users").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.username == '0427')]").exists())
                .andExpect(jsonPath("$..passwordHash").doesNotExist());
    }

    @Test void invalidAccountsAndMismatchedExtensionsCannotBypassClientValidation() throws Exception {
        long before = users.count();
        for (String account : List.of("", "123", "12345", "abcd", "１２３４", "-123")) {
            mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", account, "password", "chosen"))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"7451\",\"password\":\"chosen\",\"sipId\":\"1002\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "7452", "password", "密".repeat(25)))))
                .andExpect(status().isBadRequest());
        assertThat(users.count()).isEqualTo(before);
    }

    @Test void duplicateAndReservedIdentitiesCannotReplaceExistingAccounts() throws Exception {
        var original = users.findByUsername("1001").orElseThrow();
        String originalHash = original.getPasswordHash();
        identities.reserve("7453", "history");
        for (String account : List.of("1001", "7453")) {
            mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", account, "password", "replacement"))))
                    .andExpect(status().isConflict());
        }
        assertThat(users.findByUsername("1001").orElseThrow().getPasswordHash()).isEqualTo(originalHash);
        assertThat(users.findByUsername("7453")).isEmpty();
    }

    @Test void concurrentRegistrationsOfSameAccountCreateOnlyOneUser() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CyclicBarrier ready = new CyclicBarrier(2);
        java.util.concurrent.Callable<Integer> register = () -> {
            ready.await(3, TimeUnit.SECONDS);
            return mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"7454\",\"password\":\"chosen\"}"))
                    .andReturn().getResponse().getStatus();
        };
        long before = users.count();
        try {
            var first = executor.submit(register);
            var second = executor.submit(register);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(users.count()).isEqualTo(before + 1);
        } finally { executor.shutdownNow(); }
    }
}
