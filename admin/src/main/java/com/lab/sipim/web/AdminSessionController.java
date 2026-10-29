package com.lab.sipim.web;

import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.ApiAccessPolicy;
import com.lab.sipim.security.LoginTokens;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminSessionController {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final LoginTokens tokens;

    public AdminSessionController(UserRepository users, PasswordEncoder passwords, LoginTokens tokens) {
        this.users = users; this.passwords = passwords; this.tokens = tokens;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginController.LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null
                || request.username().isBlank() || request.password().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "请输入管理员账号和密码"));
        var user = users.findByUsername(request.username().trim()).orElse(null);
        if (user == null || !passwords.matches(request.password(), user.getPasswordHash()))
            return ResponseEntity.status(401).body(Map.of("error", "账号或密码错误"));
        if (!ApiAccessPolicy.isAdmin(user))
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员账号 1001 可以登录工作台"));
        return ResponseEntity.ok(Map.of("token", tokens.issue(user.getId()), "username", user.getUsername(), "role", "ADMIN"));
    }

    @GetMapping("/session")
    public ResponseEntity<?> session(@RequestHeader("Authorization") String authorization) {
        var user = tokens.userIdOf(MeController.bearerToken(authorization)).flatMap(users::findById).orElse(null);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        return ResponseEntity.ok(Map.of("username", user.getUsername(), "role", user.getRole()));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader("Authorization") String authorization) {
        // A browser logout must not revoke 1001's other desktop-client sessions.
        tokens.revoke(MeController.bearerToken(authorization));
        return Map.of("success", true);
    }
}
