package com.lab.sipim.web;

import com.lab.sipim.domain.FileIndex;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.FileIndexRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class FileController {

    static final String FILE_REQUIRED = "file is required";

    private final FileIndexRepository fileRepo;
    private final UserRepository userRepo;
    private final LoginTokens tokens;
    private final Path uploadDir;

    public FileController(
            FileIndexRepository fileRepo,
            UserRepository userRepo,
            LoginTokens tokens,
            @Value("${sipim.upload-dir:uploads}") String uploadDir) {
        this.fileRepo = fileRepo;
        this.userRepo = userRepo;
        this.tokens = tokens;
        this.uploadDir = Paths.get(uploadDir);
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "from", required = false) String fromUser,
            @RequestParam(value = "to", required = false) String toUserOrRoom,
            @RequestHeader(value = "Authorization", required = false) String authorization)
            throws IOException {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", FILE_REQUIRED));
        }

        String finalFrom = blank(fromUser) ? null : fromUser.trim();
        if (authorization != null && !authorization.isBlank()) {
            Optional<User> user = userFromAuthorization(authorization);
            if (user.isEmpty()) {
                return unauthorized();
            }
            if (finalFrom == null) {
                finalFrom = user.get().getUsername();
            }
        }
        if (finalFrom == null) {
            finalFrom = "anonymous";
        }
        String finalTo = blank(toUserOrRoom) ? "broadcast" : toUserOrRoom.trim();

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

    private Optional<User> userFromAuthorization(String authorization) {
        String token = MeController.bearerToken(authorization);
        if (token == null) {
            return Optional.empty();
        }
        Optional<Long> uid = tokens.userIdOf(token);
        if (uid.isEmpty()) {
            return Optional.empty();
        }
        return userRepo.findById(uid.get());
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", LoginController.UNAUTHORIZED));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
