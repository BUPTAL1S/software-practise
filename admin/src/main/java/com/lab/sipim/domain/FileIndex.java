package com.lab.sipim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "files", indexes = @jakarta.persistence.Index(name="idx_files_filter",columnList="from_user,to_user_or_room,id"))
public class FileIndex {
    @Column(name="from_user_key") private String fromUserKey;
    @Column(name="to_user_or_room_key") private String toUserOrRoomKey;
    @jakarta.persistence.PrePersist @jakarta.persistence.PreUpdate
    private void normalizeQueryKeys() {
        fromUserKey=fromUser==null?null:fromUser.trim().toLowerCase(java.util.Locale.ROOT);
        toUserOrRoomKey=toUserOrRoom==null?null:toUserOrRoom.trim().toLowerCase(java.util.Locale.ROOT);
    }


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_user", nullable = false)
    private String fromUser;

    @Column(name = "to_user_or_room", nullable = false)
    private String toUserOrRoom;

    @Column(nullable = false)
    private String path;

    @Column(nullable = false)
    private long size;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFromUser() {
        return fromUser;
    }

    public void setFromUser(String fromUser) {
        this.fromUser = fromUser;
    }

    public String getToUserOrRoom() {
        return toUserOrRoom;
    }

    public void setToUserOrRoom(String toUserOrRoom) {
        this.toUserOrRoom = toUserOrRoom;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
