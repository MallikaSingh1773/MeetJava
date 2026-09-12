package com.mallika.meetjava.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "meetings")
public class Meeting {

    @Id
    @Column(length = 12)
    private String code;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String hostName;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant endedAt;

    @Column(nullable = false)
    private boolean locked = false;

    protected Meeting() { }

    public Meeting(String code, String title, String hostName) {
        this.code = code;
        this.title = title;
        this.hostName = hostName;
    }

    public String getCode() { return code; }
    public String getTitle() { return title; }
    public String getHostName() { return hostName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEndedAt() { return endedAt; }
    public boolean isLocked() { return locked; }

    public void setTitle(String title) { this.title = title; }
    public void setLocked(boolean locked) { this.locked = locked; }
    public void end() { this.endedAt = Instant.now(); }
    public boolean isActive() { return endedAt == null; }
}
