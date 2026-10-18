package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lab.sipim.repo.FileIndexRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
@TestPropertySource(properties = "SIPIM_DB=./target/test-file.db")
class FileApiTest {

    static {
        try {
            Files.deleteIfExists(Path.of("target/test-file.db"));
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FileIndexRepository files;

    @Test
    void uploadAndDownloadFile() throws Exception {
        byte[] content = "Hello SIP File Transfer via Spring Boot!".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.txt", "text/plain", content);

        MvcResult uploadResult = mvc.perform(multipart("/api/files")
                        .file(file)
                        .param("from", "1001")
                        .param("to", "1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("report.txt"))
                .andExpect(jsonPath("$.size").value(content.length))
                .andExpect(jsonPath("$.url").isNotEmpty())
                .andExpect(jsonPath("$.from").value("1001"))
                .andExpect(jsonPath("$.to").value("1002"))
                .andReturn();

        String body = uploadResult.getResponse().getContentAsString();
        int idStart = body.indexOf("\"id\":") + 5;
        int idEnd = body.indexOf(',', idStart);
        if (idEnd < 0) idEnd = body.indexOf('}', idStart);
        long id = Long.parseLong(body.substring(idStart, idEnd).trim());

        assertThat(files.findById(id)).isPresent();

        // Download by id
        mvc.perform(get("/api/files/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"report.txt\""))
                .andExpect(content().bytes(content));
    }

    @Test
    void uploadEmptyFileReturnsBadRequest() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        mvc.perform(multipart("/api/files").file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("file is required"));
    }

    @Test
    void downloadNonExistentFileReturnsNotFound() throws Exception {
        mvc.perform(get("/api/files/999999"))
                .andExpect(status().isNotFound());
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
    void uploadWithValidTokenResolvesUser() throws Exception {
        // First login to get a real token
        MvcResult loginRes = mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1001\",\"password\":\"1001\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String loginBody = loginRes.getResponse().getContentAsString();
        int tStart = loginBody.indexOf("\"token\":\"") + 9;
        int tEnd = loginBody.indexOf('"', tStart);
        String token = loginBody.substring(tStart, tEnd);

        MockMultipartFile file = new MockMultipartFile(
                "file", "authed.txt", "text/plain", "hello authed".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/files")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("1001"))
                .andExpect(jsonPath("$.name").value("authed.txt"));
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
