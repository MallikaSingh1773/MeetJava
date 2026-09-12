package com.mallika.meetjava.model;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;

/**
 * Audit record for one remote-control grant.
 *
 * Written when control is accepted and closed when it ends, so there is a
 * permanent answer to "who controlled this machine, when, and for how long".
 * Software that can take over someone's desktop should never be able to do it
 * without leaving a trace.
 */
@Entity
@Table(name = "control_sessions")
public class ControlSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String meetingCode;

    @Column(nullable = false)
    private String hostName;

    @Column(nullable = false)
    private String controllerName;

    @Column(nullable = false)
    private Instant grantedAt = Instant.now();

    private Instant endedAt;

    @Column(nullable = false)
    private String endedReason = "";

    protected ControlSession() { }

    public ControlSession(String meetingCode, String hostName, String controllerName) {
        this.meetingCode = meetingCode;
        this.hostName = hostName;
        this.controllerName = controllerName;
    }

    public Long getId() { return id; }
    public String getMeetingCode() { return meetingCode; }
    public String getHostName() { return hostName; }
    public String getControllerName() { return controllerName; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getEndedAt() { return endedAt; }
    public String getEndedReason() { return endedReason; }

    public void end(String reason) {
        this.endedAt = Instant.now();
        this.endedReason = reason == null ? "" : reason;
    }

    public long seconds() {
        return endedAt == null ? 0 : Duration.between(grantedAt, endedAt).toSeconds();
    }
}
