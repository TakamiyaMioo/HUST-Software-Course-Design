package com.example.demo.entity;

import jakarta.persistence.*;

@Entity
public class EmailAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;    // 邮箱地址 (xxx@qq.com)
    private String password; // 邮箱授权码 (存加密后的字符串)
    private String type;     // qq 或 163

    // 所属的主账号ID
    private Long appUserId;

    public EmailAccount() {}

    public EmailAccount(String email, String password, String type, Long appUserId) {
        this.email = email;
        this.password = password;
        this.type = type;
        this.appUserId = appUserId;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getAppUserId() { return appUserId; }
    public void setAppUserId(Long appUserId) { this.appUserId = appUserId; }
}