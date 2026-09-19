package com.lab.sipim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "sip_uri", nullable = false, unique = true)
    private String sipUri;

    @Column(name = "max_members", nullable = false)
    private int maxMembers = 8;

    @Column(name = "member_count", nullable = false)
    private int memberCount = 0;

    @PrePersist
    void assignSipUri() {
        if (id != null && (sipUri == null || sipUri.isBlank())) {
            sipUri = "room-" + id;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSipUri() {
        return sipUri;
    }

    public void setSipUri(String sipUri) {
        this.sipUri = sipUri;
    }

    public int getMaxMembers() {
        return maxMembers;
    }

    public void setMaxMembers(int maxMembers) {
        this.maxMembers = maxMembers;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(int memberCount) {
        this.memberCount = memberCount;
    }
}
