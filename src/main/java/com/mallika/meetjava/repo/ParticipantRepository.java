package com.mallika.meetjava.repo;

import com.mallika.meetjava.model.Participant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {
    List<Participant> findByMeetingCodeOrderByJoinedAtAsc(String meetingCode);
    Optional<Participant> findByPeerId(String peerId);
}
