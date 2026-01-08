package com.example.demo.repository;

import com.example.demo.entity.DraftEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DraftRepository extends JpaRepository<DraftEmail, Long> {
    // 查找某个用户的草稿
    List<DraftEmail> findBySender(String sender);
}