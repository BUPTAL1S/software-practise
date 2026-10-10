package com.lab.sipim;

import static org.assertj.core.api.Assertions.assertThat;

import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@TestPropertySource(properties = "SIPIM_DB=./target/test-users.db")
class UserRepositoryTest {

    static {
        try {
            Files.deleteIfExists(Path.of("target/test-users.db"));
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired
    private UserRepository users;

    @Test
    void seedsEightDemoUsers() {
        assertThat(users.findBySipId("1001")).isPresent();
        assertThat(users.findByUsername("1008")).isPresent();
        assertThat(users.count()).isGreaterThanOrEqualTo(8);
    }

    @Test
    @Transactional
    void canInsertAnotherUser() {
        User extra = new User();
        extra.setUsername("tester");
        extra.setSipId("200");
        extra.setPasswordHash("dev-200");
        users.save(extra);

        assertThat(users.findBySipId("200")).isPresent();
    }
}
