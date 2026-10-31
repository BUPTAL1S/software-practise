package com.lab.sipim.repo;

import com.lab.sipim.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findBySipId(String sipId);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsBySipIdIgnoreCase(String sipId);

    boolean existsByAvatarUrl(String avatarUrl);
}
