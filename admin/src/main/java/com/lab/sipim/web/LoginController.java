package com.lab.sipim.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LoginController {

    static final String MISSING = "username and password are required";
    static final String UNAUTHORIZED = "unauthorized";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginTokens tokens;

    public LoginController(UserRepository users, PasswordEncoder passwordEncoder, LoginTokens tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody(required = false) LoginRequest request) {
        if (request == null || blank(request.username()) || blank(request.password())) {
            return ResponseEntity.badRequest().body(Map.of("error", MISSING));
        }
        String trimmedUser = request.username().trim();
        Optional<User> found = users.findByUsername(trimmedUser);
        if (found.isEmpty()) {
            found = users.findBySipId(trimmedUser);
        }
        if (found.isEmpty() || !passwordEncoder.matches(request.password(), found.get().getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", UNAUTHORIZED));
        }
        User user = found.get();
        String token = tokens.issue(user.getId());
        Map<String, String> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("username", user.getUsername());
        resp.put("sipId", user.getSipId());
        if (user.getAvatarUrl() != null) {
            resp.put("avatarUrl", user.getAvatarUrl());
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, String> body) {
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        } else if (body != null && body.containsKey("token")) {
            token = body.get("token");
        }
        boolean revoked = false;
        if (token != null && !token.isBlank()) {
            Optional<Long> uid = tokens.userIdOf(token);
            revoked = tokens.revoke(token);
            uid.ifPresent(tokens::revokeUser);
        }
        return ResponseEntity.ok(Map.of("success", true, "revoked", revoked, "message", "已成功登出后台会话"));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody(required = false) RegisterRequest request) {
        if (request == null || blank(request.username()) || blank(request.password())) {
            return ResponseEntity.badRequest().body(Map.of("error", MISSING));
        }
        String username = request.username().trim();
        String password = request.password().trim();
        String sipId = (request.sipId() != null && !request.sipId().isBlank())
                ? request.sipId().trim()
                : username;

        if (users.findByUsername(username).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "username already exists"));
        }
        if (users.findBySipId(sipId).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "sipId already exists"));
        }

        User user = new User();
        user.setUsername(username);
        user.setSipId(sipId);
        user.setPasswordHash(passwordEncoder.encode(password));
        User saved = users.save(user);

        String token = tokens.issue(saved.getId());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("username", saved.getUsername());
        resp.put("sipId", saved.getSipId());
        resp.put("token", token);

        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record LoginRequest(String username, String password) {
    }

    public record RegisterRequest(
            String username,
            String password,
            @JsonAlias({"sip_id", "sipId"}) String sipId) {
    }
}
