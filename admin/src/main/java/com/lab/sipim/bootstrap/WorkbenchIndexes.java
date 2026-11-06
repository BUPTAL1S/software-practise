package com.lab.sipim.bootstrap;
import java.util.List;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/** Expression indexes match the case-insensitive criteria, including old databases. */
@Component @Order(200)
public class WorkbenchIndexes implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public WorkbenchIndexes(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public void run(ApplicationArguments arguments) {
        for(String definition:List.of("idx_cdr_query_lower ON cdr(lower(caller),lower(callee_or_room),id)",
                "idx_files_query_lower ON files(lower(from_user),lower(to_user_or_room),id)",
                "idx_private_from_lower ON private_messages(lower(sender),id)",
                "idx_private_to_lower ON private_messages(lower(receiver),id)",
                "idx_room_messages_lower ON room_messages(lower(room_uri),lower(sender),id)",
                "idx_cdr_time ON cdr(started_at,id)","idx_files_time ON files(created_at,id)",
                "idx_private_time ON private_messages(created_at,id)","idx_room_messages_time ON room_messages(created_at,id)",
                "idx_audit_actor ON audit_events(actor,id)","idx_audit_action ON audit_events(action,id)"))
            jdbc.execute("CREATE INDEX IF NOT EXISTS "+definition);
    }
}
