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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.web.context.WebApplicationContext testContext;

    @org.junit.jupiter.api.BeforeEach
    void authenticateManagementRequests() {
        var tokenStore = testContext.getBean(com.lab.sipim.security.LoginTokens.class);
        var userStore = testContext.getBean(com.lab.sipim.repo.UserRepository.class);
        String adminToken = tokenStore.issue(userStore.findByUsername("1001").orElseThrow().getId());
        // Explicit headers on individual requests override this default (invalid-token tests stay meaningful).
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(testContext)
                .defaultRequest(get("/").header("Authorization", "Bearer " + adminToken)).build();
    }


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
                .andExpect(content().string(containsString("url=admin.html")));

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

    @Autowired
    private com.lab.sipim.security.LoginTokens tokens;

    @Test
    void duplicateLoginDoesNotIncreaseOnlineCount() throws Exception {
        mvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1097\",\"password\":\"p1097\"}"))
                .andExpect(status().isCreated());

        String res1 = mvc.perform(get("/api/users/online"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", org.hamcrest.Matchers.hasItem("1097")))
                .andReturn().getResponse().getContentAsString();
        int count1 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res1).get("count").asInt();

        // 相同用户再次登录，不增加计数 (按用户去重)
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1097\",\"password\":\"p1097\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/users/online"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(count1))
                .andExpect(jsonPath("$.users", org.hamcrest.Matchers.hasItem("1097")));

        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("url=admin.html")));
    }

    @Test
    void logoutRevokesTokenAndDecreasesOnlineCount() throws Exception {
        String reg = mvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1099\",\"password\":\"p1099\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(reg).get("token").asText();

        mvc.perform(get("/api/users/online"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", org.hamcrest.Matchers.hasItem("1099")));

        // 验证 POST /api/logout 注销会话后即时从在线列表中扣减
        mvc.perform(post("/api/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 验证用户已被移除
        mvc.perform(get("/api/users/online"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("1099"))));
    }

    @Test
    void authActivitySlidesExpirationWindow() throws Exception {
        String reg = mvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1096\",\"password\":\"p1096\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(reg).get("token").asText();

        var initialSession = tokens.getSession(token).orElseThrow();
        long initialExpireAt = initialSession.expireAt();

        // 暂停 30 毫秒，模拟时间推移
        Thread.sleep(30);

        // 发送带 Token 的鉴权请求 (GET /api/me)
        mvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("1096"));

        var updatedSession = tokens.getSession(token).orElseThrow();
        long updatedExpireAt = updatedSession.expireAt();

        // 断言鉴权访问成功将过期截止时间向后滑动推移
        org.junit.jupiter.api.Assertions.assertTrue(
                updatedExpireAt > initialExpireAt,
                "Active authentication access must slide token expiration window forward (initial="
                        + initialExpireAt + ", updated=" + updatedExpireAt + ")");
    }

    @Test
    void testTokenTtlExpirationRemovesFromOnline() throws Exception {
        mvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1098\",\"password\":\"p1098\"}"))
                .andExpect(status().isCreated());

        // 验证在在线列表中
        mvc.perform(get("/api/users/online"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", org.hamcrest.Matchers.hasItem("1098")));

        // 模拟会话超时过期
        tokens.expireAllForTesting();

        // Checking the management-only list requires a new administrator session.
        String freshAdmin = tokens.issue(testContext.getBean(com.lab.sipim.repo.UserRepository.class)
                .findByUsername("1001").orElseThrow().getId());

        // 触发刷新，验证已超时用户被自动驱逐出在线列表
        mvc.perform(get("/api/users/online").header("Authorization", "Bearer " + freshAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.users", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("1098"))));
    }
}
