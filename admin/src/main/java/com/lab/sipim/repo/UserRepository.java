package com.lab.sipim.repo;

import com.lab.sipim.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findBySipId(String sipId);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsBySipIdIgnoreCase(String sipId);

    boolean existsByAvatarUrl(String avatarUrl);

    @Transactional
    @Modifying
    @Query("""
            update User u set u.disabled = :disabled, u.disabledAt = :changedAt, u.disabledReason = :reason
            where u.id = :id and u.disabled <> :disabled
            """)
    int updateAccountState(@Param("id") Long id, @Param("disabled") boolean disabled,
            @Param("changedAt") String changedAt, @Param("reason") String reason);
}
