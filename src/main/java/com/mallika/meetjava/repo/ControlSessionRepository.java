package com.mallika.meetjava.repo;

import com.mallika.meetjava.model.ControlSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ControlSessionRepository extends JpaRepository<ControlSession, Long> {
    List<ControlSession> findByMeetingCodeOrderByGrantedAtDesc(String meetingCode);
}
