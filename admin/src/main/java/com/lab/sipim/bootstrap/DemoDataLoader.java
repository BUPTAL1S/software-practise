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
    private final com.lab.sipim.repo.RoomRepository rooms;
    private final PasswordEncoder passwordEncoder;

    public DemoDataLoader(
            UserRepository users,
            com.lab.sipim.repo.RoomRepository rooms,
            PasswordEncoder passwordEncoder) {
        this.users = users;
        this.rooms = rooms;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (users.count() == 0) {
            // HTTP 密码与用户名相同，和 Kamailio 的 SIP 密码不是同一套。
            for (int i = 1001; i <= 1008; i++) {
                String id = String.valueOf(i);
                User user = new User();
                user.setUsername(id);
                user.setSipId(id);
                user.setPasswordHash(passwordEncoder.encode(id));
                user.setRole(i == 1001 ? "ADMIN" : "USER");
                users.save(user);
            }
            log.info("inserted {} demo users (1001-1008)", users.count());
        }

        // 兼容已有数据库：只把 1001 提升为管理员。
        users.findByUsername("1001").ifPresent(u -> {
            if (!"ADMIN".equalsIgnoreCase(u.getRole())) {
                u.setRole("ADMIN");
                users.save(u);
                log.info("promoted 1001 to ADMIN for chat audit");
            }
        });

        if (rooms.count() == 0) {
            com.lab.sipim.domain.Room r1 = new com.lab.sipim.domain.Room();
            r1.setName("公共研讨室 101");
            r1.setSipUri("room-101");
            r1.setMaxMembers(8);
            r1.setMemberCount(0);
            rooms.save(r1);

            com.lab.sipim.domain.Room r2 = new com.lab.sipim.domain.Room();
            r2.setName("多方会议室 102");
            r2.setSipUri("room-102");
            r2.setMaxMembers(8);
            r2.setMemberCount(0);
            rooms.save(r2);
            log.info("inserted {} demo rooms (room-101, room-102)", rooms.count());
        }
    }
}
