package com.lab.sipim.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Tracks the last room heartbeat sent by each client.
 *
 * <p>FreeSWITCH keeps a bridged conference member alive even after the client
 * process is killed, so conference membership alone cannot tell us whether a
 * participant is still there. The client sends an HTTP heartbeat while it is in
 * a room; the ESL reconciliation loop can then remove members whose heartbeat
 * has gone stale.
 */
@Component
public class RoomPresenceRegistry {

    private final Map<String, Map<String, Long>> lastSeen = new ConcurrentHashMap<>();
    private final long startedAt = System.currentTimeMillis();

    public void heartbeat(String roomKey, String user) {
        if (isBlank(roomKey) || isBlank(user)) {
            return;
        }
        lastSeen
                .computeIfAbsent(normalize(roomKey), ignored -> new ConcurrentHashMap<>())
                .put(user.trim(), System.currentTimeMillis());
    }

    public boolean isFresh(String roomKey, String user, long ttlMillis) {
        if (isBlank(roomKey) || isBlank(user)) {
            return false;
        }
        Map<String, Long> users = lastSeen.get(normalize(roomKey));
        if (users == null) {
            return false;
        }
        Long seen = users.get(user.trim());
        return seen != null && System.currentTimeMillis() - seen <= ttlMillis;
    }

    public boolean hasHeartbeat(String roomKey, String user) {
        if (isBlank(roomKey) || isBlank(user)) {
            return false;
        }
        Map<String, Long> users = lastSeen.get(normalize(roomKey));
        return users != null && users.containsKey(user.trim());
    }

    public void forget(String roomKey, String user) {
        if (isBlank(roomKey) || isBlank(user)) {
            return;
        }
        Map<String, Long> users = lastSeen.get(normalize(roomKey));
        if (users != null) {
            users.remove(user.trim());
        }
    }

    public void forgetRoom(String roomKey) {
        if (!isBlank(roomKey)) {
            lastSeen.remove(normalize(roomKey));
        }
    }

    /** Do not remove members during the first few seconds after a backend restart. */
    public long startupAgeMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    private static String normalize(String roomKey) {
        String value = roomKey.trim().toLowerCase();
        int at = value.indexOf('@');
        if (at > 0) {
            value = value.substring(0, at);
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
