package com.lab.sipim.web;

import com.lab.sipim.domain.FileIndex;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.FileIndexRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import com.lab.sipim.security.ApiAccessPolicy;
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
    private final Path uploadDir;

    public FileController(FileIndexRepository fileRepo, UserRepository userRepo, LoginTokens tokens,
            @org.springframework.beans.factory.annotation.Value("${sipim.files.upload-dir:uploads}") String uploadDir) {
        this.fileRepo = fileRepo;
        this.userRepo = userRepo;
        this.tokens = tokens;
        this.uploadDir = Paths.get(uploadDir);
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
            } else if (!com.lab.sipim.security.ApiAccessPolicy.isAdmin(u.get())
                    && !com.lab.sipim.security.ApiAccessPolicy.isIdentity(u.get(), finalFrom)) {
                return ResponseEntity.status(403).body(Map.of("error", "不能冒用其他用户上传文件"));
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
        String safeName = originalName.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\\\":*?<>|]", "_");
        if (safeName.isBlank() || safeName.equals(".") || safeName.equals("..")) safeName = "upload.bin";
        if (safeName.length() > 180) safeName = safeName.substring(safeName.length() - 180);
        String storedName = UUID.randomUUID() + "_" + safeName;
        Path targetPath = uploadDir.resolve(storedName);

        try (var input = file.getInputStream()) {
            Files.copy(input, targetPath);
        } catch (IOException failure) {
            Files.deleteIfExists(targetPath);
            throw failure;
        }

        FileIndex index = new FileIndex();
        index.setFromUser(finalFrom);
        index.setToUserOrRoom(finalTo);
        index.setPath(targetPath.toString());
        index.setSize(file.getSize());
        index.setCreatedAt(Instant.now());
        FileIndex saved;
        try {
            saved = fileRepo.saveAndFlush(index);
        } catch (RuntimeException failure) {
            Files.deleteIfExists(targetPath);
            throw failure;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", safeName);
        resp.put("size", saved.getSize());
        resp.put("url", "/api/files/" + saved.getId());
        resp.put("from", saved.getFromUser());
        resp.put("to", saved.getToUserOrRoom());

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/files")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(value = "from", required = false) String fromUser,
            @RequestParam(value = "to", required = false) String toUserOrRoom,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {

        List<FileIndex> list;
        if (fromUser != null && !fromUser.isBlank()) {
            list = fileRepo.findByFromUserOrderByIdDesc(fromUser.trim());
        } else if (toUserOrRoom != null && !toUserOrRoom.isBlank()) {
            list = fileRepo.findByToUserOrRoomOrderByIdDesc(toUserOrRoom.trim());
        } else {
            list = fileRepo.findAllByOrderByIdDesc();
        }

        if (limit > 0 && list.size() > limit) {
            list = list.subList(0, limit);
        }

        List<Map<String, Object>> resp = new ArrayList<>();
        for (FileIndex idx : list) {
            Path p = Paths.get(idx.getPath());
            String fileName = p.getFileName().toString();
            int underscoreIdx = fileName.indexOf('_');
            String originalName = (underscoreIdx > 0) ? fileName.substring(underscoreIdx + 1) : fileName;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", idx.getId());
            m.put("name", originalName);
            m.put("size", idx.getSize());
            m.put("url", "/api/files/" + idx.getId());
            m.put("from", idx.getFromUser());
            m.put("to", idx.getToUserOrRoom());
            m.put("created_at", idx.getCreatedAt().toString());
            m.put("createdAt", idx.getCreatedAt().toString());
            resp.add(m);
        }

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<Resource> download(@PathVariable("id") Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Optional<FileIndex> found = fileRepo.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FileIndex index = found.get();
        // Merely labelling an upload as "profile" must not publish a private file.
        boolean avatar = "profile".equals(index.getToUserOrRoom()) && userRepo.existsByAvatarUrl("/api/files/" + id);
        if (!avatar) {
            User viewer = tokens.userIdOf(MeController.bearerToken(authorization)).flatMap(userRepo::findById).orElse(null);
            if (viewer == null) return ResponseEntity.status(401).build();
            String recipient = Optional.ofNullable(index.getToUserOrRoom()).orElse("");
            String roomKey = recipient.replaceFirst("(?i)^sip:", "").split("@", 2)[0];
            // Rooms currently form a public directory: their attachments require a logged-in account.
            if (!ApiAccessPolicy.isAdmin(viewer) && !ApiAccessPolicy.isIdentity(viewer, index.getFromUser())
                    && !ApiAccessPolicy.isIdentity(viewer, recipient) && !"broadcast".equals(recipient)
                    && !roomKey.matches("room-[A-Za-z0-9_-]+")) return ResponseEntity.status(403).build();
        }
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
