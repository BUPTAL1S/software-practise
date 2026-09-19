package com.lab.sipim.repo;

import com.lab.sipim.domain.Room;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

    Optional<Room> findBySipUri(String sipUri);
}
