package com.lab.sipim.web;

import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::from).toList();
    }

    public record UserView(Long id, String username, String sipId) {
        static UserView from(User user) {
            return new UserView(user.getId(), user.getUsername(), user.getSipId());
        }
    }
}
