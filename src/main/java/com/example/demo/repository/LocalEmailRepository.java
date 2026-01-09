package com.example.demo.repository;

import com.example.demo.entity.LocalEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LocalEmailRepository extends JpaRepository<LocalEmail, Long> {
    List<LocalEmail> findByFolderId(Long folderId);
    void deleteByFolderId(Long folderId);
}