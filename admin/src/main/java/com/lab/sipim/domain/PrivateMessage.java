package com.lab.sipim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A persisted one-to-one chat message.
 *
 * <p>Status values:
 * PENDING   - stored but not delivered yet (recipient offline / SIP failed)
 * DELIVERED - recipient received it (SIP 200 or offline pull acknowledged)
 * FAILED    - delivery failed for a reason other than "offline"
 */
@Entity
@Table(
        name = "private_messages",
        indexes = {
            @Index(name = "idx_private_sender", columnList = "sender"),
            @Index(name = "idx_private_receiver", columnList = "receiver"),
            @Index(name = "idx_private_status", columnList = "status")
        })
public class PrivateMessage {

    public static final String PENDING = "PENDING";
    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sender;

    @Column(nullable = false)
    private String receiver;

    @Column(length = 4000, nullable = false)
    private String content;

    @Column(name = "is_file", nullable = false)
    private boolean isFile;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public PrivateMessage() {}

    public PrivateMessage(String sender, String receiver, String content, boolean isFile, String status) {
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
        this.isFile = isFile;
        this.status = normalizeStatus(status);
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static String normalizeStatus(String value) {
        if (value == null) {
            return PENDING;
        }
        String s = value.trim().toUpperCase();
        if (DELIVERED.equals(s) || FAILED.equals(s) || PENDING.equals(s)) {
            return s;
        }
        return PENDING;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = normalizeStatus(status);
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
