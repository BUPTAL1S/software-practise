package com.lab.sipim.web;

import com.lab.sipim.domain.FileIndex;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.FileIndexRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class FileController {

    private final FileIndexRepository fileRepo;
    private final UserRepository userRepo;
    private final LoginTokens tokens;
    private final Path uploadDir = Paths.get("uploads");

    public FileController(FileIndexRepository fileRepo, UserRepository userRepo, LoginTokens tokens) {
        this.fileRepo = fileRepo;
        this.userRepo = userRepo;
        this.tokens = tokens;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "from", required = false) String fromUser,
            @RequestParam(value = "to", required = false) String toUserOrRoom,
            @RequestHeader(value = "Authorization", required = false) String authorization) throws IOException {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }

        String finalFrom = fromUser;
        if (authorization != null && !authorization.isBlank()) {
            String token = MeController.bearerToken(authorization);
            if (token == null) {
                return ResponseEntity.status(401).body(Map.of("error", LoginController.UNAUTHORIZED));
            }
            Optional<Long> uid = tokens.userIdOf(token);
            if (uid.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of("error", LoginController.UNAUTHORIZED));
            }
            Optional<User> u = userRepo.findById(uid.get());
            if (u.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of("error", LoginController.UNAUTHORIZED));
            }
            if (finalFrom == null || finalFrom.isBlank()) {
                finalFrom = u.get().getUsername();
            }
        }
        if (finalFrom == null || finalFrom.isBlank()) {
            finalFrom = "anonymous";
        }
        String finalTo = (toUserOrRoom != null && !toUserOrRoom.isBlank()) ? toUserOrRoom.trim() : "broadcast";

        Files.createDirectories(uploadDir);
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "upload.bin";
        }
        String safeName = Paths.get(originalName).getFileName().toString();
        String storedName = UUID.randomUUID() + "_" + safeName;
        Path targetPath = uploadDir.resolve(storedName);

        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        FileIndex index = new FileIndex();
        index.setFromUser(finalFrom);
        index.setToUserOrRoom(finalTo);
        index.setPath(targetPath.toString());
        index.setSize(file.getSize());
        index.setCreatedAt(Instant.now());
        FileIndex saved = fileRepo.save(index);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", safeName);
        resp.put("size", saved.getSize());
        resp.put("url", "/api/files/" + saved.getId());
        resp.put("from", saved.getFromUser());
        resp.put("to", saved.getToUserOrRoom());

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<Resource> download(@PathVariable("id") Long id) {
        Optional<FileIndex> found = fileRepo.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FileIndex index = found.get();
        Path filePath = Paths.get(index.getPath());
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(filePath);
        String fileName = filePath.getFileName().toString();
        int underscoreIdx = fileName.indexOf('_');
        String downloadName = (underscoreIdx > 0) ? fileName.substring(underscoreIdx + 1) : fileName;

        String contentType;
        try {
            contentType = Files.probeContentType(filePath);
        } catch (IOException e) {
            contentType = null;
        }
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(index.getSize()))
                .body(resource);
    }
}
