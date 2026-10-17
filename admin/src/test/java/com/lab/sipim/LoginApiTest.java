package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lab.sipim.repo.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "SIPIM_DB=./target/test-login.db")
class LoginApiTest {

    static {
        try {
            Files.deleteIfExists(Path.of("target/test-login.db"));
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void loginSucceedsAndSecondTokenAlsoWorks() throws Exception {
        String first = login("1001", "1001");
        String second = login("1001", "1001");
        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(second).isNotEqualTo(first);
        me(first);
        me(second);
    }

    @Test
    void wrongPasswordAndUnknownUserLookTheSame() throws Exception {
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1001\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.token").doesNotExist());

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"9999\",\"password\":\"9999\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void missingFieldsAreBadRequest() throws Exception {
        expectMissing("{\"username\":\"   \",\"password\":\"1001\"}");
        expectMissing("{\"username\":\"1001\"}");
        expectMissing("{}");
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("username and password are required"));
    }

    @Test
    void storedHashIsBcryptOfTheUsername() {
        String hash = users.findByUsername("1001").orElseThrow().getPasswordHash();
        assertThat(hash).isNotEqualTo("1001");
        assertThat(hash).isNotEqualTo("dev-1001");
        assertThat(passwordEncoder.matches("1001", hash)).isTrue();
    }

    @Test
    void meRejectsMissingAndUnknownTokens() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
        mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void registerUserSuccessAndCanLoginImmediately() throws Exception {
        mvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1099\",\"password\":\"secretPass\",\"sipId\":\"1099\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("1099"))
                .andExpect(jsonPath("$.sipId").value("1099"))
                .andExpect(jsonPath("$.token").isNotEmpty());

        // Can login with the registered credentials
        login("1099", "secretPass");
    }

    @Test
    void registerUserDuplicateReturnsConflict() throws Exception {
        mvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1001\",\"password\":\"any\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("username already exists"));
    }

    @Test
    void registerMissingFieldsReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("username and password are required"));
    }

    @Test
    void userListStaysAnonymousAndHidesPasswords() throws Exception {
        mvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == '1001')]").exists())
                .andExpect(jsonPath("$[?(@.username == '1008')]").exists())
                .andExpect(jsonPath("$..passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString("dev-"))));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.sipId").value(username))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"token\":\"") + "\"token\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private void me(String token) throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("1001"))
                .andExpect(jsonPath("$.sipId").value("1001"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    private void expectMissing(String json) throws Exception {
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("username and password are required"));
    }
}
