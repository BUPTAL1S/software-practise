package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.sipim.repo.PrivateMessageRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "SIPIM_DB=./target/test-private.db")
class PrivateMessageApiTest {

    static {
        try {
            Files.deleteIfExists(Path.of("target/test-private.db"));
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PrivateMessageRepository messages;

    @Autowired
    private LoginTokens tokens;

    @Autowired
    private ObjectMapper mapper;

    @Autowired private com.lab.sipim.repo.RoomMessageRepository roomMessages;

    @Test void incrementalPagesNeverSkipMessagesWhileInitialPageShowsRecentHistory() throws Exception {
        String token = tokens.issue(users.findByUsername("1002").orElseThrow().getId());
        String peer = "cursor-" + java.util.UUID.randomUUID();
        var ids = new java.util.ArrayList<Long>();
        var roomIds = new java.util.ArrayList<Long>();
        for (int i = 0; i < 5; i++) {
            ids.add(messages.saveAndFlush(new com.lab.sipim.domain.PrivateMessage("1002", peer, "page" + i, false, "PENDING")).getId());
            roomIds.add(roomMessages.saveAndFlush(new com.lab.sipim.domain.RoomMessage("room-101", "1002", "page" + i, false)).getId());
        }
        for (int page = 0; page < 2; page++) {
            long cursor = ids.get(page * 2), roomCursor = roomIds.get(page * 2);
            mvc.perform(get("/api/messages").param("peer", peer).param("afterId", Long.toString(cursor)).param("limit", "2")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ids.get(page * 2 + 1)))
                .andExpect(jsonPath("$[1].id").value(ids.get(page * 2 + 2)));
            mvc.perform(get("/api/rooms/room-101/messages").param("afterId", Long.toString(roomCursor)).param("limit", "2")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(roomIds.get(page * 2 + 1)))
                .andExpect(jsonPath("$[1].id").value(roomIds.get(page * 2 + 2)));
        }
        mvc.perform(get("/api/messages").param("peer", peer).param("limit", "2").header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$[0].id").value(ids.get(3))).andExpect(jsonPath("$[1].id").value(ids.get(4)));
    }

    @Test
    void adminIsOnly1001() {
        assertThat(users.findByUsername("1001").orElseThrow().getRole()).isEqualTo("ADMIN");
        assertThat(users.findByUsername("1002").orElseThrow().getRole()).isEqualTo("USER");
    }

    @Test
    void offlineMessageRemainsPendingUntilRecipientAcks() throws Exception {
        String token1001 = tokens.issue(users.findByUsername("1001").orElseThrow().getId());
        String token1002 = tokens.issue(users.findByUsername("1002").orElseThrow().getId());

        Map<String, Object> req = Map.of(
                "receiver", "1002",
                "content", "离线消息测试",
                "isFile", false,
                "status", "PENDING");
        mvc.perform(post("/api/messages")
                        .header("Authorization", "Bearer " + token1001)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sender").value("1001"))
                .andExpect(jsonPath("$.receiver").value("1002"));

        // Recipient inbox sees it.
        mvc.perform(get("/api/messages/pending")
                        .header("Authorization", "Bearer " + token1002))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].content").value("离线消息测试"));

        long id = messages.findAllByOrderByIdDesc().get(0).getId();
        mvc.perform(patch("/api/messages/" + id + "/status")
                        .header("Authorization", "Bearer " + token1002)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        mvc.perform(get("/api/messages?peer=1001")
                        .header("Authorization", "Bearer " + token1002))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DELIVERED"));
    }

    @Test
    void onlyAdminCanReadAllChats() throws Exception {
        long id1001 = users.findByUsername("1001").orElseThrow().getId();
        long id1002 = users.findByUsername("1002").orElseThrow().getId();
        String token1001 = tokens.issue(id1001);
        String token1002 = tokens.issue(id1002);

        mvc.perform(get("/api/admin/messages")
                        .header("Authorization", "Bearer " + token1001))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/messages")
                        .header("Authorization", "Bearer " + token1002))
                .andExpect(status().isForbidden());
    }
}
