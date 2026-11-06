package com.lab.sipim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "cdr", indexes = @jakarta.persistence.Index(name="idx_cdr_filter",columnList="caller,callee_or_room,id"))
public class Cdr {
    @Column(name="caller_key") private String callerKey;
    @Column(name="callee_or_room_key") private String calleeOrRoomKey;
    @jakarta.persistence.PrePersist @jakarta.persistence.PreUpdate
    private void normalizeQueryKeys() {
        callerKey=caller==null?null:caller.trim().toLowerCase(java.util.Locale.ROOT);
        calleeOrRoomKey=calleeOrRoom==null?null:calleeOrRoom.trim().toLowerCase(java.util.Locale.ROOT);
    }


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_id")
    private String callId;

    @Column(nullable = false)
    private String caller;

    @Column(name = "callee_or_room", nullable = false)
    private String calleeOrRoom;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_sec", nullable = false)
    private int durationSec;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getCaller() {
        return caller;
    }

    public void setCaller(String caller) {
        this.caller = caller;
    }

    public String getCalleeOrRoom() {
        return calleeOrRoom;
    }

    public void setCalleeOrRoom(String calleeOrRoom) {
        this.calleeOrRoom = calleeOrRoom;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public int getDurationSec() {
        return durationSec;
    }

    public void setDurationSec(int durationSec) {
        this.durationSec = durationSec;
    }
}
