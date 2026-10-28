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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.crypto.password.PasswordEncoder;

@RestController
@RequestMapping("/api")
public class MeController {

    private final UserRepository users;
    private final LoginTokens tokens;
    private final PasswordEncoder passwords;

    public MeController(UserRepository users, LoginTokens tokens, PasswordEncoder passwords) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
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
        if (user.isEmpty()) return unauthorized();
        User u = user.get();
        java.util.LinkedHashMap<String, String> resp = new java.util.LinkedHashMap<>();
        resp.put("username", u.getUsername());
        resp.put("sipId", u.getSipId());
        resp.put("nickname", u.getNickname());
        resp.put("role", u.getRole() == null ? "USER" : u.getRole());
        if (u.getAvatarUrl() != null) {
            resp.put("avatarUrl", u.getAvatarUrl());
        }
        return ResponseEntity.ok(resp);
    }

    @PatchMapping("/me/profile")
    public ResponseEntity<?> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) ProfileRequest request) {
        Optional<User> found = authenticated(authorization);
        if (found.isEmpty()) return unauthorized();
        String nickname = request == null || request.nickname() == null ? "" : request.nickname().strip();
        if (nickname.isEmpty() || nickname.codePointCount(0, nickname.length()) > 32
                || nickname.codePoints().anyMatch(Character::isISOControl)) {
            return ResponseEntity.badRequest().body(Map.of("error", "昵称须为 1–32 个字符，不能包含控制字符"));
        }
        User user = found.get();
        user.setNickname(nickname);
        users.saveAndFlush(user);
        return ResponseEntity.ok(Map.of("nickname", user.getNickname(), "username", user.getUsername()));
    }

    @PostMapping("/me/password")
    public ResponseEntity<?> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) PasswordRequest request) {
        Optional<User> found = authenticated(authorization);
        if (found.isEmpty()) return unauthorized();
        if (request == null || request.currentPassword() == null || request.newPassword() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请填写原密码和新密码"));
        }
        User user = found.get();
        if (!passwords.matches(request.currentPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(403).body(Map.of("error", "原密码不正确"));
        }
        String next = request.newPassword();
        if (next.isBlank() || next.codePointCount(0, next.length()) < 6
                || next.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码至少 6 个字符，UTF-8 长度不超过 72 字节"));
        }
        if (request.currentPassword().equals(next)) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码不能与原密码相同"));
        }
        user.setPasswordHash(passwords.encode(next));
        users.saveAndFlush(user);
        tokens.revokeUser(user.getId());
        // Rotate this session, revoke all other sessions; never accept a target user or role.
        return ResponseEntity.ok(Map.of("token", tokens.issue(user.getId()), "username", user.getUsername()));
    }

    private Optional<User> authenticated(String authorization) {
        return tokens.userIdOf(bearerToken(authorization)).flatMap(users::findById);
    }

    public record ProfileRequest(String nickname) {}
    public record PasswordRequest(String currentPassword, String newPassword) {}

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
