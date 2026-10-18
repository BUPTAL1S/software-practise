package com.lab.sipim.security;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Manages HTTP session tokens with configurable TTL, sliding expiration, and explicit logout revocation.
 */
@Component
public class LoginTokens {

    public record TokenSession(Long userId, long expireAt) {}

    public static final long DEFAULT_TTL_MS = 2 * 3600 * 1000L; // 2 hours

    private final ConcurrentHashMap<String, TokenSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile long ttlMs = DEFAULT_TTL_MS;

    public void setTtlMsForTesting(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public long getTtlMs() {
        return ttlMs;
    }

    public String issue(Long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        long expireAt = System.currentTimeMillis() + ttlMs;
        sessions.put(token, new TokenSession(userId, expireAt));
        return token;
    }

    public Optional<Long> userIdOf(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        TokenSession session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (now > session.expireAt()) {
            sessions.remove(token);
            return Optional.empty();
        }
        // Sliding expiration on active access
        sessions.put(token, new TokenSession(session.userId(), now + ttlMs));
        return Optional.of(session.userId());
    }

    public Optional<TokenSession> getSession(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(token));
    }

    public boolean revoke(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return sessions.remove(token) != null;
    }

    public void revokeUser(Long userId) {
        if (userId == null) {
            return;
        }
        sessions.entrySet().removeIf(entry -> userId.equals(entry.getValue().userId()));
    }

    /**
     * Distinct user IDs who still hold valid, unexpired login tokens.
     */
    public Set<Long> onlineUserIds() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> now > entry.getValue().expireAt());
        Set<Long> userIds = new HashSet<>();
        for (TokenSession session : sessions.values()) {
            userIds.add(session.userId());
        }
        return userIds;
    }

    public void clear() {
        sessions.clear();
    }

    public void expireAllForTesting() {
        long expiredTime = System.currentTimeMillis() - 1000L;
        sessions.replaceAll((k, s) -> new TokenSession(s.userId(), expiredTime));
    }
}
