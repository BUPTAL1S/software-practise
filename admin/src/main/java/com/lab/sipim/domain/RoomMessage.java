package com.lab.sipim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "room_messages", indexes = @jakarta.persistence.Index(name="idx_room_messages_filter",columnList="room_uri,sender,id"))
public class RoomMessage {
    @Column(name="room_uri_key") private String roomUriKey;
    @Column(name="sender_key") private String senderKey;
    @jakarta.persistence.PrePersist @jakarta.persistence.PreUpdate
    private void normalizeQueryKeys() {
        roomUriKey=roomUri==null?null:roomUri.trim().toLowerCase(java.util.Locale.ROOT);
        senderKey=sender==null?null:sender.trim().toLowerCase(java.util.Locale.ROOT);
    }


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_uri", nullable = false)
    private String roomUri;

    @Column(nullable = false)
    private String sender;

    @Column(length = 4000, nullable = false)
    private String content;

    @Column(name = "is_file", nullable = false)
    private boolean isFile;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public RoomMessage() {}

    public RoomMessage(String roomUri, String sender, String content, boolean isFile) {
        this.roomUri = roomUri;
        this.sender = sender;
        this.content = content;
        this.isFile = isFile;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoomUri() {
        return roomUri;
    }

    public void setRoomUri(String roomUri) {
        this.roomUri = roomUri;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isFile() {
        return isFile;
    }

    public void setFile(boolean file) {
        isFile = file;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
