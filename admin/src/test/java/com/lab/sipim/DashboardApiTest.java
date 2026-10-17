package com.lab.sipim;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "SIPIM_DB=./target/test-dash.db")
class DashboardApiTest {

    static {
        try {
            Files.deleteIfExists(Path.of("target/test-dash.db"));
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void healthCheckReturnsOk() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void indexAndAdminHtmlAreServedDirectly() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Java SIP & Admin")))
                .andExpect(content().string(containsString("statTotalCalls")));

        mvc.perform(get("/admin.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Java SIP & Admin")))
                .andExpect(content().string(containsString("statTotalCalls")));
    }

    @Test
    void usersApiPostCreatesUser() throws Exception {
        mvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1088\",\"password\":\"p1088\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("1088"))
                .andExpect(jsonPath("$.sipId").value("1088"));

        // Query user by identifier
        mvc.perform(get("/api/users/1088"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("1088"));
    }

    @Test
    void onlineUsersCountsDistinctLogins() throws Exception {
        mvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1099\",\"password\":\"p1099\"}"))
                .andExpect(status().isCreated());

        String afterRegister = mvc.perform(get("/api/users/online"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", org.hamcrest.Matchers.hasItem("1099")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        int countAfterRegister = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(afterRegister)
                .get("count")
                .asInt();

        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1099\",\"password\":\"p1099\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/users/online"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(countAfterRegister))
                .andExpect(jsonPath("$.users", org.hamcrest.Matchers.hasItem("1099")));

        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("statOnlineUsers")));
    }
}
