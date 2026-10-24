package com.lab.sipim.web;

import com.lab.sipim.domain.RoomMessage;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.RoomMessageRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only view of persisted room chat (room_messages). */
@RestController
@RequestMapping("/api")
public class AdminRoomMessageController {

    private final RoomMessageRepository messages;
    private final UserRepository users;
    private final LoginTokens tokens;

    public AdminRoomMessageController(
            RoomMessageRepository messages, UserRepository users, LoginTokens tokens) {
        this.messages = messages;
        this.users = users;
        this.tokens = tokens;
    }

    @GetMapping("/admin/room-messages")
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "room", required = false) String roomFilter,
            @RequestParam(value = "sender", required = false) String senderFilter,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        Optional<User> current = currentUser(authorization);
        if (current.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", LoginController.UNAUTHORIZED));
        }
        String role = current.get().getRole();
        if (role == null || !"ADMIN".equalsIgnoreCase(role.trim())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "仅管理员账号 1001 可以查看聊天室记录"));
        }
        String room = trim(roomFilter);
        String sender = trim(senderFilter);
        List<RoomMessage> all = messages.findAllByOrderByIdDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (RoomMessage message : all) {
            if (room != null && !room.isEmpty()
                    && !message.getRoomUri().equalsIgnoreCase(room)) {
                continue;
            }
            if (sender != null && !sender.isEmpty()
                    && !message.getSender().equalsIgnoreCase(sender)) {
                continue;
            }
            result.add(view(message));
            if (limit > 0 && result.size() >= limit) {
                break;
            }
        }
        return ResponseEntity.ok(result);
    }

    private Optional<User> currentUser(String authorization) {
        String token = MeController.bearerToken(authorization);
        if (token == null) {
            return Optional.empty();
        }
        return tokens.userIdOf(token).flatMap(users::findById);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> view(RoomMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", message.getId());
        map.put("room_uri", message.getRoomUri());
        map.put("roomUri", message.getRoomUri());
        map.put("sender", message.getSender());
        map.put("content", message.getContent());
        map.put("is_file", message.isFile());
        map.put("isFile", message.isFile());
        map.put("created_at", message.getCreatedAt() == null ? null : message.getCreatedAt().toString());
        map.put("createdAt", message.getCreatedAt() == null ? null : message.getCreatedAt().toString());
        return map;
    }
}
