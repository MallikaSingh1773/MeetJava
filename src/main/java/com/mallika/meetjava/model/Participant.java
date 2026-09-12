package com.mallika.meetjava.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "participants")
public class Participant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String meetingCode;

    /** Stable id for this browser tab inside the meeting. Used as the WebRTC peer id. */
    @Column(nullable = false, length = 40)
    private String peerId;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private boolean host;

    @Column(nullable = false)
    private Instant joinedAt = Instant.now();

    private Instant leftAt;

    protected Participant() { }

    public Participant(String meetingCode, String peerId, String displayName, boolean host) {
        this.meetingCode = meetingCode;
        this.peerId = peerId;
        this.displayName = displayName;
        this.host = host;
    }

    public Long getId() { return id; }
    public String getMeetingCode() { return meetingCode; }
    public String getPeerId() { return peerId; }
    public String getDisplayName() { return displayName; }
    public boolean isHost() { return host; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLeftAt() { return leftAt; }

    public void markLeft() { this.leftAt = Instant.now(); }
}
