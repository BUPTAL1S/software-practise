package com.lab.sipim.web;

import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;
    private final LoginController loginController;

    public UserController(UserRepository users, LoginController loginController) {
        this.users = users;
        this.loginController = loginController;
    }

    @GetMapping
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::from).toList();
    }

    @GetMapping("/{identifier}")
    public ResponseEntity<UserView> getOne(@PathVariable("identifier") String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        String trimmed = identifier.trim();
        Optional<User> found = Optional.empty();
        try {
            long id = Long.parseLong(trimmed);
            found = users.findById(id);
        } catch (NumberFormatException ignored) {
        }
        if (found.isEmpty()) {
            found = users.findByUsername(trimmed);
        }
        if (found.isEmpty()) {
            found = users.findBySipId(trimmed);
        }
        return found.map(u -> ResponseEntity.ok(UserView.from(u)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) LoginController.RegisterRequest request) {
        return loginController.register(request);
    }

    public record UserView(Long id, String username, String sipId) {
        static UserView from(User user) {
            return new UserView(user.getId(), user.getUsername(), user.getSipId());
        }
    }
}
