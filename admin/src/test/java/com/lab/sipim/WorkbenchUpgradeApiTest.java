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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.lab.sipim.service.AccountManagement accounts;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagers;
    @Autowired com.lab.sipim.service.IdentityReservations identities;
    @org.springframework.boot.test.mock.mockito.SpyBean com.lab.sipim.service.AdminAudit audit;
    @org.springframework.boot.test.mock.mockito.SpyBean com.lab.sipim.bootstrap.ApplicationReadiness readiness;
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
    @Test void exportsAllMatchingRowsAndPersistsAudit() throws Exception {
        for(int i=0;i<126;i++)call("1002",i);
        var pending=mvc.perform(get("/api/admin/workbench/cdr/export").header("Authorization","Bearer "+admin))
            .andExpect(request().asyncStarted()).andReturn();
        var done=mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn();
        String csv=new String(done.getResponse().getContentAsByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF\"id\""));assertEquals(127,csv.lines().count());
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='DATA_EXPORT' AND result='SUCCESS'",Long.class)>0);
        mvc.perform(get("/api/admin/workbench/operations").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(2)).andExpect(jsonPath("$.esl.enabled").value(false));
        mvc.perform(get("/api/admin/workbench/audit/page").header("Authorization","Bearer "+admin).param("action","DATA_EXPORT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].action").value("DATA_EXPORT"));
    }
    @Test void accountChangeRollsBackIfAuditCannotBeWritten() {
        var user=users.findByUsername("1002").orElseThrow();user.setDisabled(false);users.saveAndFlush(user);
        org.mockito.Mockito.doThrow(new IllegalStateException("test write failure")).when(audit)
            .record("1001","USER_DISABLE","1002","SUCCESS","account state changed");
        try {
            assertThrows(IllegalStateException.class,()->accounts.state(user.getId(),true,"test","1001"));
            assertFalse(users.findById(user.getId()).orElseThrow().isDisabled());
        } finally {org.mockito.Mockito.reset(audit);}
    }
    @Test void tenThousandRecordsLoadOnlyOnePageWithinTheLocalLatencyBudget() throws Exception {
        call("1002",0);
        Object timestamp=jdbc.queryForObject("SELECT started_at FROM cdr LIMIT 1",Object.class);
        String plan=jdbc.queryForList("EXPLAIN QUERY PLAN SELECT * FROM cdr WHERE caller_key='1001' AND callee_or_room_key='1002' ORDER BY id DESC LIMIT 25").toString();
        assertTrue(plan.contains("idx_cdr_query_keys"),plan);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            java.util.List<Object[]> batch=new java.util.ArrayList<>();
            for(int i=1;i<10000;i++)batch.add(new Object[]{"1001","1002",timestamp,10,"1001","1002"});
            jdbc.batchUpdate("INSERT INTO cdr(caller,callee_or_room,started_at,duration_sec,caller_key,callee_or_room_key) VALUES(?,?,?,?,?,?)",batch);
        });
        var statistics=entityManagers.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean original=statistics.isStatisticsEnabled();statistics.setStatisticsEnabled(true);statistics.clear();
        try {
            long[] times=new long[20];
            for(int i=0;i<20;i++) {
                long before=statistics.getEntityLoadCount(),start=System.nanoTime();
                var page=queries.page("cdr",java.util.Map.of("size","25","caller","1001","callee","1002"));
                times[i]=System.nanoTime()-start;
                assertEquals(10000,page.total());assertEquals(25,page.items().size());
                assertEquals(25,statistics.getEntityLoadCount()-before);
            }
            java.util.Arrays.sort(times);
            System.out.println("PERFORMANCE: records=10000 page=25 samples=20 p95_ms="+times[18]/1_000_000.0+" entities_per_request=25");
            assertTrue(times[18]<java.util.concurrent.TimeUnit.SECONDS.toNanos(1));
        } finally {statistics.setStatisticsEnabled(original);}
        call("1002",10001);
        mvc.perform(get("/api/admin/workbench/cdr/export").header("Authorization","Bearer "+admin))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("匹配记录超过10000条，请缩小筛选范围后导出"));
    }
    @Test void historicalIdentityIsReservedWithoutCreatingOrAssigningAnAccount() throws Exception {
        String alias="retired-"+java.util.UUID.randomUUID();call(alias,0);identities.run(null);
        assertTrue(users.findByUsername(alias).isEmpty());
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + admin).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("username",alias.toUpperCase(),"password","secret"))))
            .andExpect(status().isConflict());
        String ordinary=tokens.issue(users.findByUsername("1002").orElseThrow().getId());
        mvc.perform(get("/api/admin/workbench/operations").header("Authorization","Bearer "+ordinary)).andExpect(status().isForbidden());
    }
    @Test void everyPageAndFileExportResolveWithoutLegacyDownloadRouteCollision() throws Exception {
        for(String resource:java.util.List.of("cdr","files","messages","room-messages","users","rooms")) {
            mvc.perform(get("/api/admin/workbench/"+resource+"/page").header("Authorization","Bearer "+admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        }
        var pending=mvc.perform(get("/api/admin/workbench/files/export").header("Authorization","Bearer "+admin))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
        for(String path:java.util.List.of("/api/files","/api/admin/messages","/api/admin/room-messages")) {
            mvc.perform(get(path).header("Authorization","Bearer "+admin).param("user","").param("room","").param("sender","").param("peer",""))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
            mvc.perform(get(path).header("Authorization","Bearer "+admin).param("limit","0")).andExpect(status().isBadRequest());
        }
    }
    @Test void initializationRejectsAccountCreationUntilIdentityBackfillIsReady() throws Exception {
        org.mockito.Mockito.doReturn(false).when(readiness).isReady();
        try {
            mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("starting"));
            mvc.perform(post("/api/register").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"username\":\"startup-race\",\"password\":\"secret\"}"))
                .andExpect(status().isServiceUnavailable());
            assertTrue(users.findByUsername("startup-race").isEmpty());
        } finally {org.mockito.Mockito.reset(readiness);}
    }
}
