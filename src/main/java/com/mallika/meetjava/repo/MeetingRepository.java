package com.mallika.meetjava.repo;

import com.mallika.meetjava.model.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, String> { }
