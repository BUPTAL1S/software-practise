package com.lab.sipim.repo;

import com.lab.sipim.domain.Cdr;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CdrRepository extends JpaRepository<Cdr, Long> {

    List<Cdr> findAllByOrderByIdDesc();

    List<Cdr> findByCallerOrderByIdDesc(String caller);

    @Query("SELECT c FROM Cdr c WHERE c.calleeOrRoom = :calleeOrRoom ORDER BY c.id DESC")
    List<Cdr> findByCalleeOrRoomOrderByIdDesc(@Param("calleeOrRoom") String calleeOrRoom);

    List<Cdr> findByCallId(String callId);

    @Query("SELECT c FROM Cdr c WHERE LOWER(c.calleeOrRoom) LIKE 'room%' OR LOWER(c.calleeOrRoom) LIKE 'sip:room%'")
    List<Cdr> findRoomCalls();

    @Query("SELECT c FROM Cdr c WHERE c.caller = :caller AND c.calleeOrRoom = :callee AND c.startedAt >= :since AND c.startedAt <= :until ORDER BY c.id DESC")
    List<Cdr> findDuplicates(
            @Param("caller") String caller,
            @Param("callee") String callee,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("SELECT COALESCE(SUM(c.durationSec), 0) FROM Cdr c")
    long sumTotalDurationSec();

    @Query("SELECT DISTINCT c.calleeOrRoom FROM Cdr c WHERE c.calleeOrRoom LIKE 'room%' OR c.calleeOrRoom LIKE '%room-%'")
    List<String> findDistinctRoomUris();
}
