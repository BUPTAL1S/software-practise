package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.sipim.repo.FileIndexRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "SIPIM_DB=./target/test-file.db",
            "sipim.upload-dir=./target/test-uploads"
        })
class FileApiTest {

    private static final Path TEST_DB = Path.of("target/test-file.db");
    private static final Path TEST_UPLOADS = Path.of("target/test-uploads");

    static {
        try {
            Files.deleteIfExists(TEST_DB);
            deleteRecursively(TEST_UPLOADS);
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FileIndexRepository files;

    @Autowired
    private ObjectMapper mapper;

    @AfterAll
    static void deleteTestStore() throws IOException {
        deleteRecursively(TEST_UPLOADS);
        try {
            Files.deleteIfExists(TEST_DB);
        } catch (IOException ignored) {
            // Hikari/SQLite still holds the file on Windows until the JVM exits.
        }
    }

    @Test
    void uploadAndDownloadFile() throws Exception {
        byte[] content = "Hello SIP File Transfer via Spring Boot!".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "report.txt", "text/plain", content);

        MvcResult uploadResult = mvc.perform(multipart("/api/files")
                        .file(file)
                        .param("from", "1001")
                        .param("to", "1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("report.txt"))
                .andExpect(jsonPath("$.size").value(content.length))
                .andExpect(jsonPath("$.from").value("1001"))
                .andExpect(jsonPath("$.to").value("1002"))
                .andReturn();

        JsonNode body = mapper.readTree(uploadResult.getResponse().getContentAsString());
        long id = body.get("id").asLong();
        assertThat(body.get("url").asText()).isEqualTo("/api/files/" + id);
        assertThat(files.findById(id)).isPresent();
        assertThat(Files.exists(TEST_UPLOADS)).isTrue();
        try (var listed = Files.list(TEST_UPLOADS)) {
            assertThat(listed.anyMatch(path -> path.getFileName().toString().endsWith("_report.txt")))
                    .isTrue();
        }

        mvc.perform(get("/api/files/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"report.txt\""))
                .andExpect(content().bytes(content));
    }

    @Test
    void uploadWithoutFromOrToUsesAnonymousAndBroadcast() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/files").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("anonymous"))
                .andExpect(jsonPath("$.to").value("broadcast"))
                .andExpect(jsonPath("$.name").value("note.txt"));
    }

    @Test
    void uploadStripsPathFromOriginalName() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "../secret.txt", "text/plain", "secret".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mvc.perform(multipart("/api/files").file(file).param("from", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("secret.txt"))
                .andReturn();

        long id = mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/files/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"secret.txt\""));
    }

    @Test
    void uploadEmptyFileReturnsBadRequest() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        mvc.perform(multipart("/api/files").file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("file is required"));
    }

    @Test
    void uploadWithoutFileReturnsBadRequest() throws Exception {
        mvc.perform(multipart("/api/files").param("from", "1001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("file is required"));
    }

    @Test
    void downloadNonExistentFileReturnsNotFound() throws Exception {
        mvc.perform(get("/api/files/999999")).andExpect(status().isNotFound());
    }

    @Test
    void uploadWithInvalidTokenReturnsUnauthorized() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.txt", "text/plain", "data".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/files")
                        .file(file)
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void uploadWithValidTokenAndNoFromRecordsThatUser() throws Exception {
        String token = login("1001", "1001");
        MockMultipartFile file = new MockMultipartFile(
                "file", "authed.txt", "text/plain", "hello authed".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/files")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("1001"))
                .andExpect(jsonPath("$.to").value("broadcast"))
                .andExpect(jsonPath("$.name").value("authed.txt"));
    }

    @Test
    void uploadKeepsFormFromWhenTokenIsAlsoPresent() throws Exception {
        String token = login("1001", "1001");
        MockMultipartFile file = new MockMultipartFile(
                "file", "both.txt", "text/plain", "both".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/files")
                        .file(file)
                        .param("from", "1002")
                        .param("to", "1003")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("1002"))
                .andExpect(jsonPath("$.to").value("1003"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult loginRes = mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(loginRes.getResponse().getContentAsString()).get("token").asText();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void listFilesReturnsMetadataArray() throws Exception {
        byte[] content = "Listable test file".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "list_doc.txt", "text/plain", content);

        mvc.perform(multipart("/api/files")
                        .file(file)
                        .param("from", "1003")
                        .param("to", "room-101"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/files?from=1003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("list_doc.txt"))
                .andExpect(jsonPath("$[0].from").value("1003"))
                .andExpect(jsonPath("$[0].to").value("room-101"))
                .andExpect(jsonPath("$[0].url").isNotEmpty());
    }
}
