package com.lab.sipim;
import com.lab.sipim.bootstrap.SchemaMigration;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class SchemaMigrationTest {
    @Test void upgradesLegacyDatabaseTwiceWithoutChangingAccounts() throws Exception {
        Path file=Files.createTempFile(Path.of("target"),"migration-",".db");
        SQLiteDataSource ds=new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:"+file);
        try {
            try(var c=ds.getConnection();var s=c.createStatement()) {
                s.execute("CREATE TABLE users(id INTEGER PRIMARY KEY,username TEXT,password_hash TEXT)");
                s.execute("INSERT INTO users VALUES(9,'old-user','keep-hash')");
                s.execute("CREATE TABLE rooms(id INTEGER PRIMARY KEY,name TEXT)");
                s.execute("INSERT INTO rooms VALUES(7,'old-room')");
            }
            SchemaMigration.migrate(ds);SchemaMigration.migrate(ds);
            try(var c=ds.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM users")) {
                assertTrue(r.next());assertEquals(9,r.getLong("id"));assertEquals("keep-hash",r.getString("password_hash"));assertEquals(0,r.getInt("disabled"));assertFalse(r.next());
            }
            try(var c=ds.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM schema_versions")) {assertTrue(r.next());assertEquals(1,r.getInt(1));}
        } finally {Files.deleteIfExists(file);}
    }
}
