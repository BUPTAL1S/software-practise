package com.lab.sipim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import com.lab.sipim.service.AvatarService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"SIPIM_DB=./target/test-identity-boundary.db", "freeswitch.esl.enabled=false",
        "sipim.avatar.fs-dir=./target/identity-boundary/avatars", "sipim.avatar.upload-dir=./target/identity-boundary/uploads"})
@AutoConfigureMockMvc
class IdentityBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AvatarService avatars;
    @Autowired UserRepository users;
    @Autowired LoginTokens tokens;

    @Test void startupCreatesDefaultAvatar() throws Exception {
        Path avatar = avatars.getFsAvatarDir().resolve("default.png");
        assertThat(Files.size(avatar)).isPositive();
        assertThat(javax.imageio.ImageIO.read(avatar.toFile())).isNotNull();
    }

    @Test void rejectPathsReservedNamesAndControlCharactersBeforeCreatingAccount() throws Exception {
        long count = users.count();
        for (String bad : new String[]{"../escape", "..\\escape", "C:\\escape", "a/b", "a\nb", "CON", "room-101", "x".repeat(65)}) {
            for (String field : new String[]{"username", "sipId"}) {
                var request = new java.util.HashMap<>(Map.of("username", "safe-account", "sipId", "safe-extension", "password", "secret"));
                request.put(field, bad);
                mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request))).andExpect(status().isBadRequest());
            }
        }
        assertThat(users.count()).isEqualTo(count);
    }

    @Test void passwordsRoundTripExactlyAndOversizeUtf8IsRejected() throws Exception {
        String name="spaces-"+UUID.randomUUID();
        var request=Map.of("username",name,"password"," secret 密码 ");
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username",name,"password","secret 密码"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username","large-pass","password","密".repeat(25)))))
                .andExpect(status().isBadRequest());
    }

    @Test void reserveAliasesIgnoringCase() throws Exception {
        String name="case-"+UUID.randomUUID();
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username",name,"password","secret"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username",name.toUpperCase(java.util.Locale.ROOT),"password","secret"))))
                .andExpect(status().isConflict());
    }

    @Test void legacyUnsafeIdentityCannotWriteAvatarOrClearItsProfile() {
        User legacy=new User(); legacy.setUsername("safe-user"); legacy.setSipId("../escaped");
        legacy.setAvatarUrl("keep-this-avatar");
        Path escaped=avatars.getFsAvatarDir().toAbsolutePath().resolve("../escaped.png").normalize();
        assertThatThrownBy(() -> avatars.resetToDefaultAvatar(legacy)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> avatars.ensureUserAvatar(legacy)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> avatars.generatePlaceholderAvatar("1002","../escaped"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(escaped)).isFalse();
        assertThat(legacy.getAvatarUrl()).isEqualTo("keep-this-avatar");
    }
}
