package com.mallika.meetjava.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String meetingCode;

    @Column(nullable = false)
    private String senderName;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false)
    private Instant sentAt = Instant.now();

    protected ChatMessage() { }

    public ChatMessage(String meetingCode, String senderName, String body) {
        this.meetingCode = meetingCode;
        this.senderName = senderName;
        this.body = body;
    }

    public Long getId() { return id; }
    public String getMeetingCode() { return meetingCode; }
    public String getSenderName() { return senderName; }
    public String getBody() { return body; }
    public Instant getSentAt() { return sentAt; }
}
