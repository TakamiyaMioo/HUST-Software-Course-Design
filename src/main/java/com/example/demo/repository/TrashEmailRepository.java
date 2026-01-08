package com.example.demo.repository;

import com.example.demo.entity.TrashEmail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrashEmailRepository extends JpaRepository<TrashEmail, Long> {
}