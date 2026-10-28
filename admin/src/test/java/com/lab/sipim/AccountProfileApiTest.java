package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"SIPIM_DB=./target/test-account-profile.db", "freeswitch.esl.enabled=false"})
class AccountProfileApiTest {
  static {
    try { Files.deleteIfExists(Path.of("target/test-account-profile.db")); }
    catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired LoginTokens tokens;
  @Autowired PasswordEncoder encoder;
  @Autowired ObjectMapper json;
  String token;

  @BeforeEach void prepare() {
    tokens.clear();
    var user = users.findByUsername("1002").orElseThrow();
    user.setPasswordHash(encoder.encode("1002")); user.setNickname(null);
    users.saveAndFlush(user);
    token = tokens.issue(user.getId());
  }

  @Test void nicknameChangesOnlyCallerAndKeepsAccountAndRoles() throws Exception {
    mvc.perform(patch("/api/me/profile").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
            Map.of("nickname", "小组成员 \"Alice\" }", "username", "1001", "role", "ADMIN"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("1002"));
    var user = users.findByUsername("1002").orElseThrow();
    assertThat(user.getNickname()).isEqualTo("小组成员 \"Alice\" }");
    assertThat(user.getSipId()).isEqualTo("1002");
    assertThat(user.getRole()).isEqualTo("USER");
    assertThat(users.findByUsername("1001").orElseThrow().getRole()).isEqualTo("ADMIN");
    mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value(user.getNickname()));
  }

  @Test void profileRequiresLoginAndValidNickname() throws Exception {
    mvc.perform(patch("/api/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"A\"}"))
        .andExpect(status().isUnauthorized());
    for (String value : new String[]{" ", "a".repeat(33), "a\nb"}) {
      mvc.perform(patch("/api/me/profile").header("Authorization", "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("nickname", value))))
          .andExpect(status().isBadRequest());
    }
  }

  @Test void passwordChangesHashLoginAndRevokesOtherSessionsWithoutChangingAdmin() throws Exception {
    var user = users.findByUsername("1002").orElseThrow();
    String anotherSession = tokens.issue(user.getId());
    String admin = tokens.issue(users.findByUsername("1001").orElseThrow().getId());
    String body = mvc.perform(post("/api/me/password").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("currentPassword", "1002", "newPassword", "New secret 2026"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String rotated = json.readTree(body).get("token").asText();
    assertThat(tokens.userIdOf(token)).isEmpty();
    assertThat(tokens.userIdOf(anotherSession)).isEmpty();
    assertThat(tokens.userIdOf(admin)).isPresent();
    assertThat(tokens.userIdOf(rotated)).contains(user.getId());
    String hash = users.findById(user.getId()).orElseThrow().getPasswordHash();
    assertThat(hash).isNotEqualTo("New secret 2026");
    assertThat(encoder.matches("New secret 2026", hash)).isTrue();
    mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"1002\",\"password\":\"1002\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"1002\",\"password\":\"New secret 2026\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("USER"));
  }

  @Test void wrongCurrentOrWeakPasswordDoesNotChangeCredentials() throws Exception {
    mvc.perform(post("/api/me/password").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"wrong\",\"newPassword\":\"abcdef\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/me/password").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"1002\",\"newPassword\":\"abc\"}"))
        .andExpect(status().isBadRequest());
    assertThat(encoder.matches("1002", users.findByUsername("1002").orElseThrow().getPasswordHash())).isTrue();
  }

  @Test void passwordRequiresAuthentication() throws Exception {
    mvc.perform(post("/api/me/password").contentType(MediaType.APPLICATION_JSON)
        .content("{\"currentPassword\":\"1002\",\"newPassword\":\"abcdef\"}"))
        .andExpect(status().isUnauthorized());
  }
}
