package com.mallika.meetjava.web;

import com.mallika.meetjava.model.Meeting;
import com.mallika.meetjava.service.MeetingService;
import com.mallika.meetjava.signal.RoomRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Small JSON API next to the server-rendered pages, so a React or mobile client can use the same backend. */
@RestController
@RequestMapping("/api/meetings")
public class MeetingRestController {

    private final MeetingService meetings;
    private final RoomRegistry registry;

    public MeetingRestController(MeetingService meetings, RoomRegistry registry) {
        this.meetings = meetings;
        this.registry = registry;
    }

    public record CreateRequest(String title, String hostName) { }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest req) {
        Meeting m = meetings.createMeeting(req.title(), req.hostName());
        return Map.of("code", m.getCode(), "title", m.getTitle(), "hostName", m.getHostName());
    }

    @GetMapping("/{code}")
    public ResponseEntity<?> get(@PathVariable String code) {
        return meetings.find(code)
                .<ResponseEntity<?>>map(m -> ResponseEntity.ok(Map.of(
                        "code", m.getCode(),
                        "title", m.getTitle(),
                        "hostName", m.getHostName(),
                        "active", m.isActive(),
                        "liveParticipants", registry.size(m.getCode()))))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "not found")));
    }

    @GetMapping("/{code}/chat")
    public List<Map<String, Object>> chat(@PathVariable String code) {
        return meetings.chatHistory(code).stream()
                .map(m -> Map.<String, Object>of(
                        "senderName", m.getSenderName(),
                        "body", m.getBody(),
                        "sentAt", m.getSentAt().toString()))
                .toList();
    }

    /** Remote-control audit trail: who controlled whom, when, and for how long. */
    @GetMapping("/{code}/control-sessions")
    public List<Map<String, Object>> controlSessions(@PathVariable String code) {
        return meetings.controlHistory(code).stream()
                .map(cs -> Map.<String, Object>of(
                        "host", cs.getHostName(),
                        "controller", cs.getControllerName(),
                        "grantedAt", cs.getGrantedAt().toString(),
                        "endedAt", cs.getEndedAt() == null ? "" : cs.getEndedAt().toString(),
                        "seconds", cs.seconds(),
                        "endedReason", cs.getEndedReason()))
                .toList();
    }

    @GetMapping("/{code}/participants")
    public List<Map<String, Object>> participants(@PathVariable String code) {
        return meetings.participantsOf(code).stream()
                .map(p -> Map.<String, Object>of(
                        "displayName", p.getDisplayName(),
                        "host", p.isHost(),
                        "joinedAt", p.getJoinedAt().toString(),
                        "stillIn", p.getLeftAt() == null))
                .toList();
    }
}
