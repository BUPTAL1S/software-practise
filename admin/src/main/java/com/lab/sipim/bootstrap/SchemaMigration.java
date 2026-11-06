package com.lab.sipim.bootstrap;

import java.sql.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runs before Hibernate: upgrades existing SQLite tables without rebuilding data. */
@Configuration("schemaMigration")
public class SchemaMigration implements InitializingBean {
    private final DataSource dataSource;
    public SchemaMigration(DataSource dataSource) { this.dataSource = dataSource; }
    @Bean static BeanFactoryPostProcessor migrationBeforeJpa() {
        return factory -> {
            if (factory.containsBeanDefinition("entityManagerFactory")) {
                var definition=factory.getBeanDefinition("entityManagerFactory");
                var dependencies=new java.util.LinkedHashSet<String>();
                if(definition.getDependsOn()!=null)dependencies.addAll(java.util.List.of(definition.getDependsOn()));
                dependencies.add("schemaMigration");definition.setDependsOn(dependencies.toArray(String[]::new));
            }
        };
    }
    @Override public void afterPropertiesSet() throws Exception { migrate(dataSource); }
    public static void migrate(DataSource source) throws SQLException {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS schema_versions(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS audit_events(id INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at TEXT NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL, result TEXT NOT NULL, detail TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_events(occurred_at,id)");
                s.execute("CREATE TABLE IF NOT EXISTS identity_reservations(alias TEXT PRIMARY KEY, source TEXT NOT NULL)");
                column(c,"users","disabled","INTEGER NOT NULL DEFAULT 0");
                column(c,"users","disabled_reason","TEXT");
                column(c,"users","disabled_at","TEXT");
                column(c,"rooms","archived","INTEGER NOT NULL DEFAULT 0");
                column(c,"rooms","archived_at","TEXT");
                s.execute("INSERT OR IGNORE INTO schema_versions VALUES(1,strftime('%Y-%m-%dT%H:%M:%fZ','now'))");
                // Remove only indexes introduced by the earlier candidate build;
                // SQLite reports expression-index column names as null to Hibernate.
                for(String index:java.util.List.of("idx_cdr_query_lower","idx_files_query_lower","idx_private_from_lower","idx_private_to_lower","idx_room_messages_lower"))s.execute("DROP INDEX IF EXISTS "+index);
                queryKey(c,"cdr","caller","caller_key");
                queryKey(c,"cdr","callee_or_room","callee_or_room_key");
                queryKey(c,"files","from_user","from_user_key");
                queryKey(c,"files","to_user_or_room","to_user_or_room_key");
                queryKey(c,"private_messages","sender","sender_key");
                queryKey(c,"private_messages","receiver","receiver_key");
                queryKey(c,"room_messages","room_uri","room_uri_key");
                queryKey(c,"room_messages","sender","sender_key");
                queryKey(c,"users","username","username_key");
                queryKey(c,"rooms","sip_uri","sip_uri_key");
                s.execute("INSERT OR IGNORE INTO schema_versions VALUES(2,strftime('%Y-%m-%dT%H:%M:%fZ','now'))");
                c.commit();
            } catch (SQLException error) { c.rollback(); throw error; }
        }
    }
    private static void queryKey(Connection c,String table,String source,String key)throws SQLException {
        boolean exists=false;
        try(var s=c.createStatement();var rows=s.executeQuery("PRAGMA table_info("+table+")")){while(rows.next())if(source.equals(rows.getString("name")))exists=true;}
        if(!exists)return;
        column(c,table,key,"TEXT");
        try(var s=c.createStatement()){s.execute("UPDATE "+table+" SET "+key+"=lower(trim("+source+")) WHERE "+key+" IS NULL");}
    }
    private static void column(Connection c, String table, String column, String declaration) throws SQLException {
        boolean exists=false, found=false;
        try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) {
            while(r.next()) {exists=true; if(column.equals(r.getString("name")))found=true;}
        }
        if(exists && !found) try(Statement s=c.createStatement()) {s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+declaration);}
    }
}
