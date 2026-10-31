package com.lab.sipim;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lab.sipim.domain.Cdr;
import com.lab.sipim.domain.Room;
import com.lab.sipim.repo.*;
import com.lab.sipim.security.LoginTokens;
import com.lab.sipim.service.RoomCallStatistics;
import com.lab.sipim.service.RoomPresenceRegistry;
import com.lab.sipim.web.RoomController;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RoomCallStatisticsTest {
    private static Cdr call(String room, int start, int seconds) {
        Cdr value = new Cdr(); value.setCalleeOrRoom(room);
        value.setStartedAt(Instant.parse("2026-10-30T08:00:00Z").plusSeconds(start));
        value.setDurationSec(seconds); return value;
    }

    @Test void roomAliasesShareOneTimelineAndOtherRoomsRemainSeparate() {
        var peaks = RoomCallStatistics.calculate(List.of(call("room-101", 0, 30),
                call("sip:room-101@lab", 5, 10), call("SIP:ROOM-101@lab", 6, 10),
                call("room-102", 0, 3), call("room-102", 10, 3)));
        assertThat(peaks).containsEntry("room-101", 3).containsEntry("room-102", 1);
    }

    @Test void boundarySemanticsArePreservedAndZeroLengthCallsAreExcluded() {
        var peaks = RoomCallStatistics.calculate(List.of(call("room-101", 0, 10),
                call("room-101", 10, 10), call("room-101", 10, 0), call("1002", 0, 20)));
        assertThat(peaks).containsExactlyEntriesOf(Map.of("room-101", 2));
    }

    @Test void largeRoomListReadsRoomCallsOnlyOnce() {
        CdrRepository cdrs = mock(CdrRepository.class);
        RoomRepository rooms = mock(RoomRepository.class);
        List<Room> roomList = new ArrayList<>();
        List<Cdr> calls = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Room room = new Room(); room.setSipUri("room-" + i); room.setName("Room " + i);
            roomList.add(room);
            for (int j = 0; j < 50; j++) calls.add(call(room.getSipUri(), j, 60));
        }
        when(rooms.findAllByOrderByIdAsc()).thenReturn(roomList);
        when(cdrs.findRoomCalls()).thenReturn(calls);
        var controller = new RoomController(rooms, mock(RoomMessageRepository.class), mock(UserRepository.class),
                mock(LoginTokens.class), new RoomCallStatistics(cdrs), new RoomPresenceRegistry());
        var result = controller.listRooms().getBody();
        assertThat(result).hasSize(200);
        assertThat(result.get(199)).containsEntry("peak_members", 50);
        verify(cdrs, times(1)).findRoomCalls();
        verifyNoMoreInteractions(cdrs);
    }

    @Test void emptyRoomListAvoidsHistoryQueries() {
        CdrRepository cdrs = mock(CdrRepository.class);
        RoomRepository rooms = mock(RoomRepository.class);
        when(rooms.findAllByOrderByIdAsc()).thenReturn(List.of());
        var controller = new RoomController(rooms, mock(RoomMessageRepository.class), mock(UserRepository.class),
                mock(LoginTokens.class), new RoomCallStatistics(cdrs), new RoomPresenceRegistry());
        assertThat(controller.listRooms().getBody()).isEmpty();
        verifyNoInteractions(cdrs);
    }
}
