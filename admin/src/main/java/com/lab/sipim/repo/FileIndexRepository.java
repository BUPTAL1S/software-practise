package com.lab.sipim.repo;

import com.lab.sipim.domain.FileIndex;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileIndexRepository extends JpaRepository<FileIndex, Long> {

    List<FileIndex> findAllByOrderByIdDesc();

    List<FileIndex> findByFromUserOrderByIdDesc(String fromUser);

    @Query("SELECT f FROM FileIndex f WHERE f.toUserOrRoom = :target ORDER BY f.id DESC")
    List<FileIndex> findByToUserOrRoomOrderByIdDesc(@Param("target") String target);
}
