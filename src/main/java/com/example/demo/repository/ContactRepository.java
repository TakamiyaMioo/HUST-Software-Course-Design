package com.example.demo.repository;

import com.example.demo.entity.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ContactRepository extends JpaRepository<Contact, Long> {
    // 【新增】查找属于某个主账号的所有联系人
    List<Contact> findByAppUserId(Long appUserId);
}

//package com.example.demo.repository;
//
//import com.example.demo.entity.Contact;
//import org.springframework.data.jpa.repository.JpaRepository;
//
//public interface ContactRepository extends JpaRepository<Contact, Long> {
//}