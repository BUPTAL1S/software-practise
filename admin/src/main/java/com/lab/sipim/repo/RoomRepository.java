package com.lab.sipim.repo;

import com.lab.sipim.domain.Room;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoomRepository extends JpaRepository<Room, Long> {

    Optional<Room> findBySipUri(String sipUri);

    @Query("SELECT COALESCE(MAX(r.memberCount), 0) FROM Room r")
    int findPeakMemberCount();
}

