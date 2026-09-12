package com.mallika.meetjava.service;

import com.mallika.meetjava.model.ChatMessage;
import com.mallika.meetjava.model.ControlSession;
import com.mallika.meetjava.model.Meeting;
import com.mallika.meetjava.model.Participant;
import com.mallika.meetjava.repo.ChatMessageRepository;
import com.mallika.meetjava.repo.ControlSessionRepository;
import com.mallika.meetjava.repo.MeetingRepository;
import com.mallika.meetjava.repo.ParticipantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

@Service
public class MeetingService {

    /** Ambiguous characters (0/O, 1/I/l) removed so codes can be read out loud. */
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MeetingRepository meetings;
    private final ParticipantRepository participants;
    private final ChatMessageRepository chats;
    private final ControlSessionRepository controlSessions;

    public MeetingService(MeetingRepository meetings,
                          ParticipantRepository participants,
                          ChatMessageRepository chats,
                          ControlSessionRepository controlSessions) {
        this.meetings = meetings;
        this.participants = participants;
        this.chats = chats;
        this.controlSessions = controlSessions;
    }

    @Transactional
    public Meeting createMeeting(String title, String hostName) {
        String code;
        do {
            code = generateCode();
        } while (meetings.existsById(code));

        String safeTitle = (title == null || title.isBlank()) ? "Untitled meeting" : title.trim();
        String safeHost = (hostName == null || hostName.isBlank()) ? "Host" : hostName.trim();
        return meetings.save(new Meeting(code, safeTitle, safeHost));
    }

    /** Codes look like "abc-defg-hjk", the shape people are used to from Meet. */
    private String generateCode() {
        StringBuilder sb = new StringBuilder(12);
        int[] groups = {3, 4, 3};
        for (int g = 0; g < groups.length; g++) {
            if (g > 0) sb.append('-');
            for (int i = 0; i < groups[g]; i++) {
                sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public Optional<Meeting> find(String code) {
        return meetings.findById(normalize(code));
    }

    @Transactional
    public Participant join(String meetingCode, String peerId, String displayName) {
        String code = normalize(meetingCode);
        Meeting meeting = meetings.findById(code)
                .orElseThrow(() -> new IllegalArgumentException("No meeting with code " + code));
        boolean first = participants.findByMeetingCodeOrderByJoinedAtAsc(code).stream()
                .noneMatch(p -> p.getLeftAt() == null);
        String name = (displayName == null || displayName.isBlank()) ? "Guest" : displayName.trim();
        return participants.save(new Participant(meeting.getCode(), peerId, name, first));
    }

    @Transactional
    public void leave(String peerId) {
        participants.findByPeerId(peerId).ifPresent(p -> {
            p.markLeft();
            participants.save(p);
        });
    }

    @Transactional
    public ChatMessage saveChat(String meetingCode, String sender, String body) {
        return chats.save(new ChatMessage(normalize(meetingCode), sender, body));
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> chatHistory(String meetingCode) {
        return chats.findByMeetingCodeOrderBySentAtAsc(normalize(meetingCode));
    }

    @Transactional(readOnly = true)
    public List<Participant> participantsOf(String meetingCode) {
        return participants.findByMeetingCodeOrderByJoinedAtAsc(normalize(meetingCode));
    }

    // ------------------------------------------------------------ remote control audit

    @Transactional
    public ControlSession openControlSession(String meetingCode, String hostName, String controllerName) {
        return controlSessions.save(new ControlSession(normalize(meetingCode), hostName, controllerName));
    }

    @Transactional
    public void closeControlSession(Long id, String reason) {
        if (id == null) return;
        controlSessions.findById(id).ifPresent(cs -> {
            if (cs.getEndedAt() == null) {
                cs.end(reason);
                controlSessions.save(cs);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<ControlSession> controlHistory(String meetingCode) {
        return controlSessions.findByMeetingCodeOrderByGrantedAtDesc(normalize(meetingCode));
    }

    private String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase();
    }
}
