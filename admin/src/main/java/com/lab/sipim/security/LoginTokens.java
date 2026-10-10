package com.lab.sipim.security;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class LoginTokens {

    private final ConcurrentHashMap<String, Long> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String issue(Long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        tokens.put(token, userId);
        return token;
    }

    public Optional<Long> userIdOf(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokens.get(token));
    }
}
