package com.lab.sipim.service;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit metadata only; callers never pass credentials or message bodies. */
@Service
public class AdminAudit {
    private final JdbcTemplate jdbc;
    public AdminAudit(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @Transactional public void record(String actor,String action,String target,String result,String detail) {
        jdbc.update("INSERT INTO audit_events(occurred_at,actor,action,target,result,detail) VALUES(?,?,?,?,?,?)",
            Instant.now().toString(), bounded(actor,64),bounded(action,64),bounded(target,128),bounded(result,16),bounded(detail,256));
    }
    private static String bounded(String text,int max) {return text==null?"":text.substring(0,Math.min(max,text.length()));}
}
