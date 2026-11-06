package com.lab.sipim;
import com.lab.sipim.domain.Room;
import com.lab.sipim.repo.RoomRepository;
import com.lab.sipim.esl.EslEventListener;
import com.lab.sipim.service.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RoomManagementTest {
    @Test void archiveRequiresFreshEmptyStateAndKeepsIdentity() {
        var repo=mock(RoomRepository.class);var esl=mock(EslEventListener.class);var audit=mock(AdminAudit.class);
        var room=new Room();room.setId(7L);room.setName("old");room.setSipUri("room-7");
        when(repo.findById(7L)).thenReturn(Optional.of(room));
        var service=new RoomManagement(repo,esl,audit);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.update(7L,null,true,"1001"));
        when(esl.hasFreshRoomState()).thenReturn(true);room.setMemberCount(1);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.update(7L,null,true,"1001"));
        room.setMemberCount(0);service.update(7L,"new",true,"1001");
        assertTrue(room.isArchived());assertEquals("room-7",room.getSipUri());
        service.update(7L,null,false,"1001");assertFalse(room.isArchived());
        verify(audit).record("1001","ROOM_ARCHIVE","room-7","SUCCESS","directory state changed");
    }
}
