package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lab.sipim.bootstrap.DemoDataLoader;
import com.lab.sipim.domain.PrivateMessage;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.PrivateMessageRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
@TestPropertySource(properties = {"SIPIM_DB=./target/test-user-delete.db", "freeswitch.esl.enabled=false",
        "sipim.avatar.fs-dir=./target/test-user-delete-avatars", "sipim.avatar.upload-dir=./target/test-user-delete-uploads"})
class UserDeletionApiTest {
    static {
        try { Files.deleteIfExists(Path.of("target/test-user-delete.db")); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PrivateMessageRepository messages;
    @Autowired LoginTokens tokens;
    @Autowired PasswordEncoder passwords;
    @Autowired DemoDataLoader seeds;
    String admin;

    @BeforeEach void prepare() {
        tokens.clear();
        for (String name : List.of("1008", "1009")) {
            User target = users.findByUsername(name).orElseGet(User::new);
            target.setUsername(name);
            target.setSipId(name);
            target.setRole("USER");
            target.setPasswordHash(passwords.encode(name));
            users.saveAndFlush(target);
        }
        admin = tokens.issue(users.findByUsername("1001").orElseThrow().getId());
    }

    @Test void deletionRequiresAnAuthenticatedAdministrator() throws Exception {
        long target = users.findByUsername("1008").orElseThrow().getId();
        mvc.perform(delete("/api/users/" + target)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/" + target).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        String ordinary = tokens.issue(users.findByUsername("1002").orElseThrow().getId());
        mvc.perform(delete("/api/users/" + target).header("Authorization", "Bearer " + ordinary))
                .andExpect(status().isForbidden());
        assertThat(users.findById(target)).isPresent();
    }

    @Test void currentAndOtherAdministratorAccountsAreProtected() throws Exception {
        long ownId = users.findByUsername("1001").orElseThrow().getId();
        mvc.perform(delete("/api/users/" + ownId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
        User other = users.findByUsername("1009").orElseThrow();
        other.setRole("ADMIN");
        users.saveAndFlush(other);
        mvc.perform(delete("/api/users/" + other.getId()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
        assertThat(users.findById(ownId)).isPresent();
        assertThat(users.findById(other.getId())).isPresent();
    }

    @Test void deleteRevokesAllTargetSessionsKeepsOtherUsersAndPreservesHistory() throws Exception {
        User target = users.findByUsername("1008").orElseThrow();
        String first = tokens.issue(target.getId()), second = tokens.issue(target.getId());
        String otherToken = tokens.issue(users.findByUsername("1002").orElseThrow().getId());
        var history = messages.saveAndFlush(new PrivateMessage("1001", "1008", "保留历史", false, "DELIVERED"));
        var remaining = users.findAll().stream().filter(u -> !u.getId().equals(target.getId()))
                .map(u -> List.of(u.getId().toString(), u.getUsername(), u.getSipId(), u.getPasswordHash(), u.getRole()))
                .toList();

        mvc.perform(delete("/api/users/" + target.getId()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("1008"));
        assertThat(users.findById(target.getId())).isEmpty();
        assertThat(tokens.userIdOf(first)).isEmpty();
        assertThat(tokens.userIdOf(second)).isEmpty();
        assertThat(tokens.userIdOf(admin)).isPresent();
        assertThat(tokens.userIdOf(otherToken)).isPresent();
        assertThat(messages.findById(history.getId())).isPresent();
        mvc.perform(get("/api/users")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == '1008')]").isEmpty());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + first)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"1008\",\"password\":\"1008\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/" + target.getId()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        seeds.run();
        assertThat(users.findByUsername("1008")).isEmpty();
        assertThat(users.findAll().stream()
                .map(u -> List.of(u.getId().toString(), u.getUsername(), u.getSipId(), u.getPasswordHash(), u.getRole())).toList())
                .containsExactlyElementsOf(remaining);
    }

    @Test void deleteUsesDatabaseIdWithoutFallingBackToNumericUsernames() throws Exception {
        mvc.perform(delete("/api/users/1009").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        User target = users.findByUsername("1009").orElseThrow();
        mvc.perform(delete("/api/users/" + target.getId()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("1009"));
    }
}
