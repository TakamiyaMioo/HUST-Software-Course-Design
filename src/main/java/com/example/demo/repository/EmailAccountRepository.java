package com.example.demo.repository;

import com.example.demo.entity.EmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EmailAccountRepository extends JpaRepository<EmailAccount, Long> {
    // 查找某个用户的所有邮箱
    List<EmailAccount> findByAppUserId(Long appUserId);

    // 检查邮箱是否已被绑定
    EmailAccount findByEmail(String email);
}