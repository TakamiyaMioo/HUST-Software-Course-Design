package com.example.demo.repository;

import com.example.demo.entity.SentLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SentLogRepository extends JpaRepository<SentLog, Long> {
}
