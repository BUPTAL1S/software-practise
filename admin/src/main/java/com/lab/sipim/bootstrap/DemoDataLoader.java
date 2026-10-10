package com.lab.sipim.bootstrap;

import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DemoDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DemoDataLoader(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (users.count() > 0) {
            log.info("users already seeded, count={}", users.count());
            return;
        }
        // HTTP 密码与用户名相同，和 Kamailio 的 SIP 密码不是同一套。
        for (int i = 1001; i <= 1008; i++) {
            String id = String.valueOf(i);
            User user = new User();
            user.setUsername(id);
            user.setSipId(id);
            user.setPasswordHash(passwordEncoder.encode(id));
            users.save(user);
        }
        log.info("inserted {} demo users (1001-1008)", users.count());
    }
}
