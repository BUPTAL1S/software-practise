package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lab.sipim.domain.RoomMessage;
import com.lab.sipim.repo.RoomMessageRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "SIPIM_DB=./target/test-admin-room.db")
class AdminRoomMessageApiTest {

    static {
        try {
            Files.deleteIfExists(Path.of("target/test-admin-room.db"));
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private RoomMessageRepository rooms;
    @Autowired private UserRepository users;
    @Autowired private LoginTokens tokens;

    @Test
    void onlyAdminCanReadRoomChat() throws Exception {
        rooms.save(new RoomMessage("room-101", "1002", "room-chat-test", false));
        String admin = tokens.issue(users.findByUsername("1001").orElseThrow().getId());
        String normal = tokens.issue(users.findByUsername("1002").orElseThrow().getId());

        mvc.perform(get("/api/admin/room-messages?room=room-101")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].room_uri").value("room-101"))
                .andExpect(jsonPath("$[0].sender").value("1002"))
                .andExpect(jsonPath("$[0].content").value("room-chat-test"));

        mvc.perform(get("/api/admin/room-messages")
                        .header("Authorization", "Bearer " + normal))
                .andExpect(status().isForbidden());
    }
}
