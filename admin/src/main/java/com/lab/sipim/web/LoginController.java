package com.lab.sipim.web;

import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.util.Map;
import java.util.Optional;
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
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) {
        if (request == null || blank(request.username()) || blank(request.password())) {
            return ResponseEntity.badRequest().body(Map.of("error", MISSING));
        }
        Optional<User> found = users.findByUsername(request.username());
        if (found.isEmpty() || !passwordEncoder.matches(request.password(), found.get().getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", UNAUTHORIZED));
        }
        User user = found.get();
        String token = tokens.issue(user.getId());
        return ResponseEntity.ok(Map.of(
                "token", token,
                "username", user.getUsername(),
                "sipId", user.getSipId()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record LoginRequest(String username, String password) {
    }
}
