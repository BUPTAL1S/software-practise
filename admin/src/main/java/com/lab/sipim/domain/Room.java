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
    @Column(name="sip_uri_key") private String sipUriKey;
    @jakarta.persistence.PreUpdate
    private void normalizeQueryKeys() {
        sipUriKey=sipUri==null?null:sipUri.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Column(nullable=false) private boolean archived;
    @Column(name="archived_at") private String archivedAt;
    public boolean isArchived(){return archived;}
    public void setArchived(boolean value){archived=value;}
    public String getArchivedAt(){return archivedAt;}
    public void setArchivedAt(String value){archivedAt=value;}

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
        normalizeQueryKeys();
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
