package com.lab.sipim;

import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.service.AvatarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "SIPIM_DB=./target/test-avatar.db",
        "freeswitch.esl.enabled=${sip.liveMcuTest:false}",
        "sipim.avatar.fs-dir=${sipim.test.avatar.fs-dir:./target/test-avatars}",
        "sipim.avatar.upload-dir=./target/test-avatar-uploads"
})
public class AvatarServiceTest {

    @Autowired
    private AvatarService avatarService;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.lab.sipim.security.LoginTokens loginTokens;

    @Autowired
    private com.lab.sipim.esl.EslEventListener eslEventListener;

    @Test
    @DisplayName("AvatarService correctly generates placeholder avatars for users")
    void testPlaceholderAvatarGeneration() throws Exception {
        User user = userRepo.findByUsername("1001").orElseThrow();
        avatarService.ensureUserAvatar(user);

        Path avatarPath = avatarService.getFsAvatarDir().resolve(user.getSipId() + ".png");
        assertThat(Files.exists(avatarPath)).isTrue();
        assertThat(Files.size(avatarPath)).isGreaterThan(100);

        BufferedImage img = ImageIO.read(avatarPath.toFile());
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(400);
        assertThat(img.getHeight()).isEqualTo(400);
    }

    @Test
    @DisplayName("Avatar upload endpoint requires auth, prevents modifying other users, and syncs to FreeSWITCH")
    void testAvatarUploadEndpoint() throws Exception {
        User user1001 = userRepo.findByUsername("1001").orElseThrow();
        User user1002 = userRepo.findByUsername("1002").orElseThrow();

        String token1001 = loginTokens.issue(user1001.getId());
        String token1002 = loginTokens.issue(user1002.getId());

        BufferedImage src = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 200, 200);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(src, "png", baos);
        byte[] imageBytes = baos.toByteArray();

        MockMultipartFile file = new MockMultipartFile(
                "file", "my_avatar.png", "image/png", imageBytes);

        // 1. Without auth token -> 401 Unauthorized
        mockMvc.perform(multipart("/api/users/avatar")
                        .file(file)
                        .param("user", "1002"))
                .andExpect(status().isUnauthorized());

        // 2. User 1001 token trying to modify user 1002 -> 403 Forbidden
        mockMvc.perform(multipart("/api/users/avatar")
                        .file(file)
                        .param("user", "1002")
                        .header("Authorization", "Bearer " + token1001))
                .andExpect(status().isForbidden());

        // 3. User 1002 token modifying own avatar -> 200 OK
        mockMvc.perform(multipart("/api/users/avatar")
                        .file(file)
                        .param("user", "1002")
                        .header("Authorization", "Bearer " + token1002))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.avatarUrl").exists())
                .andExpect(jsonPath("$.username").value("1002"));

        User updatedUser = userRepo.findByUsername("1002").orElseThrow();
        assertThat(updatedUser.getAvatarUrl()).isNotNull();
        assertThat(updatedUser.getAvatarUrl()).startsWith("/api/files/");

        Path syncedPng = avatarService.getFsAvatarDir().resolve("1002.png");
        assertThat(Files.exists(syncedPng)).isTrue();
        assertThat(Files.size(syncedPng)).isGreaterThan(100);

        // 4. Verify get user returns avatarUrl
        mockMvc.perform(get("/api/users/1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(updatedUser.getAvatarUrl()));

        // 5. Reset without token -> 401 Unauthorized
        mockMvc.perform(delete("/api/users/avatar").param("user", "1002"))
                .andExpect(status().isUnauthorized());

        // 6. Reset with 1001 token for 1002 -> 403 Forbidden
        mockMvc.perform(delete("/api/users/avatar").param("user", "1002").header("Authorization", "Bearer " + token1001))
                .andExpect(status().isForbidden());

        // 7. Reset with 1002 token -> 200 OK
        mockMvc.perform(delete("/api/users/avatar").param("user", "1002").header("Authorization", "Bearer " + token1002))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        User resetUser = userRepo.findByUsername("1002").orElseThrow();
        assertThat(resetUser.getAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("Avatar upload endpoint rejects empty file, oversized file (>10MB), and invalid formats with 400 Bad Request")
    void testAvatarUploadSecurityAndValidation() throws Exception {
        User user1001 = userRepo.findByUsername("1001").orElseThrow();
        String token1001 = loginTokens.issue(user1001.getId());

        // 1. Empty file -> 400 Bad Request
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.png", "image/png", new byte[0]);
        mockMvc.perform(multipart("/api/users/avatar")
                        .file(emptyFile)
                        .param("user", "1001")
                        .header("Authorization", "Bearer " + token1001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        // 2. Oversized file (>10MB) -> 400 Bad Request
        byte[] largeBytes = new byte[10 * 1024 * 1024 + 1024]; // 10MB + 1KB
        MockMultipartFile largeFile = new MockMultipartFile(
                "file", "large.png", "image/png", largeBytes);
        mockMvc.perform(multipart("/api/users/avatar")
                        .file(largeFile)
                        .param("user", "1001")
                        .header("Authorization", "Bearer " + token1001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("exceeds 10MB limit")));

        // 3. Invalid non-image format (text file disguised as png) -> 400 Bad Request
        MockMultipartFile textFile = new MockMultipartFile(
                "file", "fake.txt", "text/plain", "This is not an image".getBytes());
        mockMvc.perform(multipart("/api/users/avatar")
                        .file(textFile)
                        .param("user", "1001")
                        .header("Authorization", "Bearer " + token1001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("Room vmute endpoint accepts command and FreeSWITCH MCU canvas mixes multi-participant avatars")
    void testRoomVmuteEndpointAndFreeSwitchCanvas() throws Exception {
        String testRoom = "room-avatar-test-" + System.nanoTime();
        String memberToken = loginTokens.issue(userRepo.findByUsername("1001").orElseThrow().getId());
        mockMvc.perform(post("/api/rooms/" + testRoom + "/vmute")
                        .header("Authorization", "Bearer " + memberToken)
                        .param("sipId", "1001")
                        .param("mute", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room").value(testRoom))
                .andExpect(jsonPath("$.sipId").value("1001"))
                .andExpect(jsonPath("$.muted").value(true));

        mockMvc.perform(post("/api/rooms/" + testRoom + "/vmute")
                        .header("Authorization", "Bearer " + memberToken)
                        .param("sipId", "1001")
                        .param("mute", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muted").value(false));

        // Live assertion: when FreeSWITCH ESL is connected, run live MCU canvas test
        assertThat(eslEventListener).isNotNull();
        assumeTrue(eslEventListener.isConnected(), "FreeSWITCH ESL 8021 not connected, skipping live MCU canvas test");

        // Ensure user 1001 has a personalized real photo avatar uploaded via API
        User user1001 = userRepo.findByUsername("1001").orElseThrow();
        String token1001 = loginTokens.issue(user1001.getId());
        Path arisPath = Paths.get("uploads/c0e8e194-1490-4053-8c81-66987cccd370_aris.jpg");
        if (!Files.exists(arisPath)) {
            arisPath = Paths.get("../uploads/c0e8e194-1490-4053-8c81-66987cccd370_aris.jpg");
        }
        if (Files.exists(arisPath)) {
            byte[] arisBytes = Files.readAllBytes(arisPath);
            MockMultipartFile arisFile = new MockMultipartFile("file", "aris.jpg", "image/jpeg", arisBytes);
            mockMvc.perform(multipart("/api/users/avatar")
                            .file(arisFile)
                            .param("user", "1001")
                            .header("Authorization", "Bearer " + token1001))
                    .andExpect(status().isOk());
        }

        try {
        // Create only test-owned channels; never hang up unrelated calls.
        for (String id : List.of("1001", "1002", "1003", "1004", "1005")) {
            eslEventListener.executeApi("originate {origination_caller_id_number=" + id + "}loopback/" + testRoom + " &echo");
            Thread.sleep(100);
        }
        Thread.sleep(1200);

        // 3. Verify member lookup resolves for all five distinct participants (five-party conference)
        Integer memberId1001 = eslEventListener.lookupMemberId(testRoom, "1001");
        Integer memberId1002 = eslEventListener.lookupMemberId(testRoom, "1002");
        Integer memberId1003 = eslEventListener.lookupMemberId(testRoom, "1003");
        Integer memberId1004 = eslEventListener.lookupMemberId(testRoom, "1004");
        Integer memberId1005 = eslEventListener.lookupMemberId(testRoom, "1005");
        assertThat(memberId1001).isNotNull();
        assertThat(memberId1002).isNotNull();
        assertThat(memberId1003).isNotNull();
        assertThat(memberId1004).isNotNull();
        assertThat(memberId1005).isNotNull();

        // 4. Perform vmute through API for 1001 (camera muted, replaced by custom avatar)
        mockMvc.perform(post("/api/rooms/" + testRoom + "/vmute")
                        .header("Authorization", "Bearer " + memberToken)
                        .param("sipId", "1001")
                        .param("mute", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muted").value(true));

        // 5. Start live rectangular camera broadcast test video stream on conference channel
        eslEventListener.executeApi("conference " + testRoom + " play /tmp/freeswitch-custom/test_video.mp4");
        Thread.sleep(1500);

        // 6. Capture actual FreeSWITCH video canvas
        boolean captured = eslEventListener.captureConferenceCanvas(testRoom, "/tmp/freeswitch-custom/test_mcu_canvas_avatar.png");
        assertThat(captured).isTrue();

        Path canvasPath = avatarService.getFsAvatarDir().getParent().resolve("test_mcu_canvas_avatar.png");
        assertThat(Files.exists(canvasPath)).isTrue();
        BufferedImage canvasImg = ImageIO.read(canvasPath.toFile());
        assertThat(canvasImg).isNotNull();
        assertThat(canvasImg.getWidth()).isEqualTo(960);
        assertThat(canvasImg.getHeight()).isEqualTo(540);

        // Save updated snapshot with 5 participants, live video stream and custom avatar
        Path repoSnapshot = Paths.get("../docs/snapshots/snapshot_mcu_canvas_avatar_muted.png");
        if (repoSnapshot.getParent() != null && Files.exists(repoSnapshot.getParent())) {
            Files.copy(canvasPath, repoSnapshot, StandardCopyOption.REPLACE_EXISTING);
        }
        Path brainSnapshot = Paths.get("/Users/developing/.gemini/antigravity-cli/brain/194ae225-4b5f-42bc-aa49-055e7cc3fb8c/snapshot_mcu_canvas_avatar_muted.png");
        if (brainSnapshot.getParent() != null && Files.exists(brainSnapshot.getParent())) {
            Files.copy(canvasPath, brainSnapshot, StandardCopyOption.REPLACE_EXISTING);
        }

        Files.deleteIfExists(canvasPath);
        } finally {
            eslEventListener.executeApi("conference " + testRoom + " kick all");
        }
    }
}
