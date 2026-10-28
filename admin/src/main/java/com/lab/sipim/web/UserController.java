package com.lab.sipim.web;

import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import com.lab.sipim.service.AvatarService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;
    private final LoginController loginController;
    private final LoginTokens tokens;
    private final AvatarService avatarService;

    public UserController(UserRepository users, LoginController loginController, LoginTokens tokens, AvatarService avatarService) {
        this.users = users;
        this.loginController = loginController;
        this.tokens = tokens;
        this.avatarService = avatarService;
    }

    @GetMapping("/online")
    public Map<String, Object> online() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Long userId : tokens.onlineUserIds()) {
            users.findById(userId).map(User::getUsername).ifPresent(names::add);
        }
        names.sort(String::compareTo);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("count", names.size());
        body.put("users", names);
        return body;
    }

    @GetMapping
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::from).toList();
    }

    @GetMapping("/{identifier}")
    public ResponseEntity<UserView> getOne(@PathVariable("identifier") String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        String trimmed = identifier.trim();
        Optional<User> found = Optional.empty();
        try {
            long id = Long.parseLong(trimmed);
            found = users.findById(id);
        } catch (NumberFormatException ignored) {
        }
        if (found.isEmpty()) {
            found = users.findByUsername(trimmed);
        }
        if (found.isEmpty()) {
            found = users.findBySipId(trimmed);
        }
        return found.map(u -> ResponseEntity.ok(UserView.from(u)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) LoginController.RegisterRequest request) {
        return loginController.register(request);
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "user", required = false) String userParam,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        Optional<User> callerUser = Optional.empty();

        if (authorization != null && !authorization.isBlank()) {
            String token = MeController.bearerToken(authorization);
            if (token != null) {
                Optional<Long> uid = tokens.userIdOf(token);
                if (uid.isPresent()) {
                    callerUser = users.findById(uid.get());
                }
            }
        }

        if (callerUser.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", LoginController.UNAUTHORIZED));
        }

        User target = callerUser.get();
        if (userParam != null && !userParam.isBlank()) {
            String u = userParam.trim();
            if (!u.equalsIgnoreCase(target.getUsername()) && !u.equalsIgnoreCase(target.getSipId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Cannot modify avatar of another user"));
            }
        }

        try {
            String avatarUrl = avatarService.saveUploadedAvatar(target, file);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "avatarUrl", avatarUrl,
                    "username", target.getUsername(),
                    "sipId", target.getSipId()
            ));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(Map.of("error", iae.getMessage()));
        } catch (IOException ioe) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to save avatar: " + ioe.getMessage()));
        }
    }

    @DeleteMapping("/avatar")
    public ResponseEntity<?> resetAvatar(
            @RequestParam(value = "user", required = false) String userParam,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        Optional<User> callerUser = Optional.empty();

        if (authorization != null && !authorization.isBlank()) {
            String token = MeController.bearerToken(authorization);
            if (token != null) {
                Optional<Long> uid = tokens.userIdOf(token);
                if (uid.isPresent()) {
                    callerUser = users.findById(uid.get());
                }
            }
        }

        if (callerUser.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", LoginController.UNAUTHORIZED));
        }

        User target = callerUser.get();
        if (userParam != null && !userParam.isBlank()) {
            String u = userParam.trim();
            if (!u.equalsIgnoreCase(target.getUsername()) && !u.equalsIgnoreCase(target.getSipId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Cannot modify avatar of another user"));
            }
        }

        avatarService.resetToDefaultAvatar(target);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Avatar reset to default",
                "username", target.getUsername()
        ));
    }

    public record UserView(Long id, String username, String sipId, String avatarUrl, String nickname) {
        static UserView from(User user) {
            return new UserView(user.getId(), user.getUsername(), user.getSipId(), user.getAvatarUrl(), user.getNickname());
        }
    }
}
