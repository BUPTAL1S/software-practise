package com.lab.sipim.service;
import com.lab.sipim.repo.RoomRepository;
import com.lab.sipim.esl.EslEventListener;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
@Service
public class RoomManagement {
    private final RoomRepository rooms;private final EslEventListener esl;private final AdminAudit audit;
    public RoomManagement(RoomRepository rooms,EslEventListener esl,AdminAudit audit){this.rooms=rooms;this.esl=esl;this.audit=audit;}
    @Transactional public Map<String,Object> update(Long id,String name,Boolean archived,String actor) {
        var room=rooms.findById(id).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"房间不存在"));
        if(name==null && archived==null)throw new IllegalArgumentException("请提供名称或归档状态");
        if(name!=null && (name.isBlank() || name.trim().length()>80))throw new IllegalArgumentException("房间名称须为1–80字");
        if(Boolean.TRUE.equals(archived) && !room.isArchived()) {
            if(!esl.hasFreshRoomState())throw new ResponseStatusException(CONFLICT,"MCU人数状态未知或过期，暂不能归档");
            if(room.getMemberCount()!=0)throw new ResponseStatusException(CONFLICT,"房间仍有成员，暂不能归档");
        }
        if(name!=null && !room.getName().equals(name.trim())) {
            room.setName(name.trim());audit.record(actor,"ROOM_RENAME",room.getSipUri(),"SUCCESS","display name changed");
        }
        if(archived!=null && archived!=room.isArchived()) {
            room.setArchived(archived);room.setArchivedAt(archived?Instant.now().toString():null);
            audit.record(actor,archived?"ROOM_ARCHIVE":"ROOM_RESTORE",room.getSipUri(),"SUCCESS","directory state changed");
        }
        rooms.saveAndFlush(room);return WorkbenchQueries.view(room);
    }
}
