package com.lab.sipim;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.sipim.repo.*;
import com.lab.sipim.domain.Cdr;
import com.lab.sipim.security.LoginTokens;
import com.lab.sipim.service.WorkbenchQueries;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"SIPIM_DB=./target/test-workbench-upgrade.db","freeswitch.esl.enabled=false"})
@AutoConfigureMockMvc
class WorkbenchUpgradeApiTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired CdrRepository calls;
    @Autowired UserRepository users; @Autowired LoginTokens tokens; @Autowired WorkbenchQueries queries;
    String admin;
    @BeforeEach void setup(){calls.deleteAll();admin=tokens.issue(users.findByUsername("1001").orElseThrow().getId());}
    void call(String peer,int second){var c=new Cdr();c.setCaller("1001");c.setCalleeOrRoom(peer);c.setStartedAt(Instant.parse("2026-11-06T00:00:00Z").plusSeconds(second));c.setDurationSec(10);calls.save(c);}
    @Test void pagesKeepTheirSnapshotWhenNewRecordsArrive() throws Exception {
        for(int i=0;i<31;i++)call("1002",i);
        var first=queries.page("cdr",java.util.Map.of("size","25"));
        assertEquals(31,first.total());assertEquals(25,first.items().size());assertTrue(first.hasNext());
        call("1003",99);
        var second=queries.page("cdr",java.util.Map.of("size","25","page","1","snapshotMaxId",""+first.snapshotMaxId()));
        assertEquals(31,second.total());assertEquals(6,second.items().size());assertFalse(second.hasNext());
        assertTrue(first.items().stream().noneMatch(second.items()::contains));
        mvc.perform(get("/api/admin/workbench/cdr/page").header("Authorization","Bearer "+admin).param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/workbench/cdr/page")).andExpect(status().isUnauthorized());
    }
    @Test void dateRangesAreHalfOpenAndCombinedWithBothParticipants() throws Exception {
        call("1002",0);call("1002",60);call("1003",30);
        mvc.perform(get("/api/admin/workbench/cdr/page").header("Authorization","Bearer "+admin)
            .param("caller","1001").param("callee","1002").param("fromTime","2026-11-06T08:00:00+08:00").param("untilTime","2026-11-06T00:01:00Z"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/workbench/cdr/page").header("Authorization","Bearer "+admin).param("fromTime","invalid"))
            .andExpect(status().isBadRequest());
    }
}
