package com.lab.sipim.bootstrap;

import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DemoDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final UserRepository users;

    public DemoDataLoader(UserRepository users) {
        this.users = users;
    }

    @Override
    public void run(String... args) {
        if (users.count() > 0) {
            log.info("users already seeded, count={}", users.count());
            return;
        }
        // Kamailio 阶段 1 用静态账号；这里只给后台自己的用户表灌测试数据，不同步 SIP 密码。
        for (int i = 100; i <= 107; i++) {
            String id = String.valueOf(i);
            User user = new User();
            user.setUsername(id);
            user.setSipId(id);
            user.setPasswordHash("dev-" + id);
            users.save(user);
        }
        log.info("inserted {} demo users (100-107)", users.count());
    }
}
