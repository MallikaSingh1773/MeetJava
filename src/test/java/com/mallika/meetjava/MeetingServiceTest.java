package com.mallika.meetjava;

import com.mallika.meetjava.model.Meeting;
import com.mallika.meetjava.model.Participant;
import com.mallika.meetjava.service.MeetingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MeetingServiceTest {

    @Autowired
    MeetingService meetings;

    @Test
    void createsMeetingWithReadableCode() {
        Meeting m = meetings.createMeeting("Standup", "Mallika");
        assertThat(m.getCode()).matches("[a-z2-9]{3}-[a-z2-9]{4}-[a-z2-9]{3}");
        assertThat(m.getTitle()).isEqualTo("Standup");
        assertThat(m.isActive()).isTrue();
    }

    @Test
    void codesAreUnique() {
        var a = meetings.createMeeting("A", "x");
        var b = meetings.createMeeting("B", "y");
        assertThat(a.getCode()).isNotEqualTo(b.getCode());
    }

    @Test
    void joinCodeIsCaseInsensitive() {
        Meeting m = meetings.createMeeting("Retro", "Mallika");
        assertThat(meetings.find(m.getCode().toUpperCase())).isPresent();
    }

    @Test
    void firstParticipantBecomesHostAndSecondDoesNot() {
        Meeting m = meetings.createMeeting("Sync", "Mallika");
        Participant first = meetings.join(m.getCode(), UUID.randomUUID().toString(), "Mallika");
        Participant second = meetings.join(m.getCode(), UUID.randomUUID().toString(), "Bob");
        assertThat(first.isHost()).isTrue();
        assertThat(second.isHost()).isFalse();
    }

    @Test
    void chatIsPersistedInOrder() {
        Meeting m = meetings.createMeeting("Chat", "Mallika");
        meetings.saveChat(m.getCode(), "Mallika", "hello");
        meetings.saveChat(m.getCode(), "Bob", "hi");
        assertThat(meetings.chatHistory(m.getCode()))
                .extracting(c -> c.getSenderName() + ":" + c.getBody())
                .containsExactly("Mallika:hello", "Bob:hi");
    }

    @Test
    void leavingMarksParticipantGone() {
        Meeting m = meetings.createMeeting("Leave", "Mallika");
        String peerId = UUID.randomUUID().toString();
        meetings.join(m.getCode(), peerId, "Mallika");
        meetings.leave(peerId);
        assertThat(meetings.participantsOf(m.getCode()))
                .allSatisfy(p -> assertThat(p.getLeftAt()).isNotNull());
    }

    @Test
    void unknownCodeReturnsEmpty() {
        assertThat(meetings.find("zzz-zzzz-zzz")).isEmpty();
    }
}
