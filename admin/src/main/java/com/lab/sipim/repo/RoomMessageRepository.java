package com.lab.sipim.repo;

import com.lab.sipim.domain.RoomMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomMessageRepository extends JpaRepository<RoomMessage, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT m FROM RoomMessage m WHERE (:room IS NULL OR m.roomUriKey=:room) AND (:sender IS NULL OR m.senderKey=:sender) ORDER BY m.id DESC")
    List<RoomMessage> searchForAdmin(@org.springframework.data.repository.query.Param("room") String room,@org.springframework.data.repository.query.Param("sender") String sender,org.springframework.data.domain.Pageable page);

    List<RoomMessage> findByRoomUriIgnoreCaseAndIdGreaterThanOrderByIdAsc(String roomUri, Long afterId, org.springframework.data.domain.Pageable page);

    List<RoomMessage> findByRoomUriIgnoreCaseOrderByIdDesc(String roomUri, org.springframework.data.domain.Pageable page);

    List<RoomMessage> findByRoomUriIgnoreCaseOrderByIdAsc(String roomUri);

    List<RoomMessage> findAllByOrderByIdDesc();
}
