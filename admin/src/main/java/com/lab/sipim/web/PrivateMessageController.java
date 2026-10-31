package com.lab.sipim.web;

import com.lab.sipim.domain.PrivateMessage;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.PrivateMessageRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private chat persistence + offline inbox + admin-only chat browser.
 *
 * <p>Private text still travels over SIP MESSAGE when both sides are online. The
 * backend is the durable copy: the sender archives the message after the SIP
 * delivery status is known, and an offline recipient pulls PENDING messages
 * over HTTP when it comes back online.
 */
@RestController
@RequestMapping("/api")
public class PrivateMessageController {

    private final PrivateMessageRepository messages;
    private final UserRepository users;
    private final LoginTokens tokens;

    public PrivateMessageController(
            PrivateMessageRepository messages, UserRepository users, LoginTokens tokens) {
        this.messages = messages;
        this.users = users;
        this.tokens = tokens;
    }

    @PostMapping("/messages")
    public ResponseEntity<?> send(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) SendMessageRequest request) {
        Optional<User> current = currentUser(authorization);
        if (current.isEmpty()) {
            return unauthorized();
        }
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "request body is required"));
        }
        String receiver = trim(request.receiver());
        String content = request.content();
        if (receiver == null || receiver.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "receiver is required"));
        }
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }
        if (content.length() > 4000) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is too long"));
        }
        String sender = current.get().getUsername();
        if (sender.equalsIgnoreCase(receiver)) {
            return ResponseEntity.badRequest().body(Map.of("error", "cannot send a message to yourself"));
        }
        PrivateMessage entity = new PrivateMessage(
                sender, receiver, content, Boolean.TRUE.equals(request.isFile()),
                PrivateMessage.normalizeStatus(request.status()));
        entity = messages.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(entity));
    }

    @GetMapping("/messages")
    public ResponseEntity<?> conversation(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "peer", required = false) String peer,
            @RequestParam(value = "afterId", defaultValue = "0") long afterId,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        Optional<User> current = currentUser(authorization);
        if (current.isEmpty()) {
            return unauthorized();
        }
        String peerName = trim(peer);
        if (peerName == null || peerName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "peer is required"));
        }
        List<PrivateMessage> list = messages.findConversation(
                current.get().getUsername(), peerName, Math.max(0, afterId),
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 500)),
                        org.springframework.data.domain.Sort.by(afterId > 0 ? org.springframework.data.domain.Sort.Direction.ASC
                                : org.springframework.data.domain.Sort.Direction.DESC, "id")));
        if (afterId <= 0) java.util.Collections.reverse(list);
        return ResponseEntity.ok(list.stream().map(this::view).toList());
    }

    /** Pending inbox used for offline delivery. */
    @GetMapping("/messages/pending")
    public ResponseEntity<?> pending(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Optional<User> current = currentUser(authorization);
        if (current.isEmpty()) {
            return unauthorized();
        }
        List<PrivateMessage> list = messages.findByReceiverIgnoreCaseAndStatusOrderByIdAsc(
                current.get().getUsername(), PrivateMessage.PENDING, org.springframework.data.domain.PageRequest.of(0, 200));
        return ResponseEntity.ok(list.stream().map(this::view).toList());
    }

    @PatchMapping("/messages/{id}/status")
    public ResponseEntity<?> updateStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") Long id,
            @RequestBody(required = false) Map<String, String> body) {
        Optional<User> current = currentUser(authorization);
        if (current.isEmpty()) {
            return unauthorized();
        }
        Optional<PrivateMessage> found = messages.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PrivateMessage message = found.get();
        String me = current.get().getUsername();
        if (!message.getSender().equalsIgnoreCase(me) && !message.getReceiver().equalsIgnoreCase(me)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "not your message"));
        }
        String status = body == null ? null : body.get("status");
        if (status == null || status.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "status is required"));
        }
        message.setStatus(status);
        messages.save(message);
        return ResponseEntity.ok(view(message));
    }

    /**
     * Admin-only chat browser. Only users with role ADMIN (currently 1001) may call it.
     */
    @GetMapping("/admin/messages")
    public ResponseEntity<?> adminList(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "user", required = false) String userFilter,
            @RequestParam(value = "peer", required = false) String peerFilter,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        Optional<User> current = currentUser(authorization);
        if (current.isEmpty()) {
            return unauthorized();
        }
        if (!isAdmin(current.get())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "仅管理员账号 1001 可以查看聊天记录"));
        }
        String user = trim(userFilter);
        String peer = trim(peerFilter);
        List<PrivateMessage> list = messages.findAllByOrderByIdDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (PrivateMessage m : list) {
            if (user != null && !user.isEmpty()
                    && !m.getSender().equalsIgnoreCase(user) && !m.getReceiver().equalsIgnoreCase(user)) {
                continue;
            }
            if (peer != null && !peer.isEmpty()
                    && !m.getSender().equalsIgnoreCase(peer) && !m.getReceiver().equalsIgnoreCase(peer)) {
                continue;
            }
            result.add(view(m));
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

    private static boolean isAdmin(User user) {
        String role = user.getRole();
        return role != null && "ADMIN".equalsIgnoreCase(role.trim());
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", LoginController.UNAUTHORIZED));
    }

    private Map<String, Object> view(PrivateMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("sender", m.getSender());
        map.put("receiver", m.getReceiver());
        map.put("content", m.getContent());
        map.put("is_file", m.isFile());
        map.put("isFile", m.isFile());
        map.put("status", m.getStatus());
        Instant created = m.getCreatedAt();
        map.put("created_at", created == null ? null : created.toString());
        map.put("createdAt", created == null ? null : created.toString());
        map.put("updated_at", m.getUpdatedAt() == null ? null : m.getUpdatedAt().toString());
        return map;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public record SendMessageRequest(
            String receiver,
            String content,
            Boolean isFile,
            String status) {}
}
