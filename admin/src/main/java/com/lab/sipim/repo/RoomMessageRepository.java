package com.lab.sipim.repo;

import com.lab.sipim.domain.RoomMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomMessageRepository extends JpaRepository<RoomMessage, Long> {

    List<RoomMessage> findByRoomUriIgnoreCaseAndIdGreaterThanOrderByIdAsc(String roomUri, Long afterId, org.springframework.data.domain.Pageable page);

    List<RoomMessage> findByRoomUriIgnoreCaseOrderByIdDesc(String roomUri, org.springframework.data.domain.Pageable page);

    List<RoomMessage> findByRoomUriIgnoreCaseOrderByIdAsc(String roomUri);

    List<RoomMessage> findAllByOrderByIdDesc();
}
