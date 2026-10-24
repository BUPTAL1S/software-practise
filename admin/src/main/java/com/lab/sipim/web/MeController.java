package com.lab.sipim.web;

import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MeController {

    private final UserRepository users;
    private final LoginTokens tokens;

    public MeController(UserRepository users, LoginTokens tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = bearerToken(authorization);
        if (token == null) {
            return unauthorized();
        }
        Optional<Long> userId = tokens.userIdOf(token);
        if (userId.isEmpty()) {
            return unauthorized();
        }
        Optional<User> user = users.findById(userId.get());
        User u = user.get();
        java.util.LinkedHashMap<String, String> resp = new java.util.LinkedHashMap<>();
        resp.put("username", u.getUsername());
        resp.put("sipId", u.getSipId());
        resp.put("role", u.getRole() == null ? "USER" : u.getRole());
        if (u.getAvatarUrl() != null) {
            resp.put("avatarUrl", u.getAvatarUrl());
        }
        return ResponseEntity.ok(resp);
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", LoginController.UNAUTHORIZED));
    }

    static String bearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.startsWith(prefix)) {
            return null;
        }
        String token = authorization.substring(prefix.length()).trim();
        if (token.isEmpty() || token.indexOf(' ') >= 0) {
            return null;
        }
        return token;
    }
}
