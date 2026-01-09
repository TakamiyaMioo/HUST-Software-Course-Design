package com.example.demo.entity;

import jakarta.persistence.*;

@Entity
public class LocalFolder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String folderName;      // 文件夹名称
    private Long emailAccountId;    // 属于哪个邮箱账号 (关联 EmailAccount 的 id)

    public LocalFolder() {}

    public LocalFolder(String folderName, Long emailAccountId) {
        this.folderName = folderName;
        this.emailAccountId = emailAccountId;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFolderName() { return folderName; }
    public void setFolderName(String folderName) { this.folderName = folderName; }
    public Long getEmailAccountId() { return emailAccountId; }
    public void setEmailAccountId(Long emailAccountId) { this.emailAccountId = emailAccountId; }
}