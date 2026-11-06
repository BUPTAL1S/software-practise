package com.lab.sipim.repo;

import com.lab.sipim.domain.PrivateMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrivateMessageRepository extends JpaRepository<PrivateMessage, Long> {
    @Query("SELECT m FROM PrivateMessage m WHERE (:user IS NULL OR m.senderKey=:user OR m.receiverKey=:user) AND (:peer IS NULL OR m.senderKey=:peer OR m.receiverKey=:peer) ORDER BY m.id DESC")
    List<PrivateMessage> searchForAdmin(@Param("user") String user,@Param("peer") String peer,org.springframework.data.domain.Pageable page);

    @Query(
            "select m from PrivateMessage m "
                    + "where ((lower(m.sender) = lower(:user) and lower(m.receiver) = lower(:peer)) "
                    + "   or (lower(m.sender) = lower(:peer) and lower(m.receiver) = lower(:user))) "
                    + "and m.id > :afterId")
    List<PrivateMessage> findConversation(
            @Param("user") String user, @Param("peer") String peer, @Param("afterId") long afterId,
            org.springframework.data.domain.Pageable page);

    List<PrivateMessage> findByReceiverIgnoreCaseAndStatusOrderByIdAsc(String receiver, String status, org.springframework.data.domain.Pageable page);

    List<PrivateMessage> findAllByOrderByIdDesc();
}
