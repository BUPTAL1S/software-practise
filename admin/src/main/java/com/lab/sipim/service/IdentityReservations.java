package com.lab.sipim.service;

import com.lab.sipim.repo.UserRepository;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reserve both current and historical identities; history never grants ownership. */
@Component @Order(100)
public class IdentityReservations implements ApplicationRunner {
    private final JdbcTemplate jdbc; private final UserRepository users;
    public IdentityReservations(JdbcTemplate jdbc,UserRepository users){this.jdbc=jdbc;this.users=users;}
    public void reserve(String alias,String source) {
        if(alias==null)return;
        String key=alias.trim().toLowerCase(Locale.ROOT);
        if(key.startsWith("sip:"))key=key.substring(4);
        if(key.contains("@"))key=key.substring(0,key.indexOf('@'));
        if(!key.matches("[a-z0-9][a-z0-9_-]{0,63}") || key.startsWith("room-"))return;
        jdbc.update("INSERT OR IGNORE INTO identity_reservations(alias,source) VALUES(?,?)",key,source);
    }
    public boolean reserved(String name){return jdbc.queryForObject("SELECT count(*) FROM identity_reservations WHERE alias=?",Long.class,name.trim().toLowerCase(Locale.ROOT))>0;}
    @Override public void run(ApplicationArguments arguments) {
        users.findAll().forEach(u->{reserve(u.getUsername(),"account");reserve(u.getSipId(),"account");});
        for(String pair:List.of("cdr:caller","cdr:callee_or_room","private_messages:sender","private_messages:receiver","room_messages:sender","files:from_user","files:to_user_or_room")) {
            String[] part=pair.split(":");
            jdbc.queryForList("SELECT DISTINCT "+part[1]+" AS identity FROM "+part[0]).forEach(row->reserve((String)row.get("identity"),"history"));
        }
    }
}
