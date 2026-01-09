package com.example.demo.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "local_emails") // 避免和某些数据库关键字冲突
public class LocalEmail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long folderId;       // 属于哪个本地文件夹 (关联 LocalFolder 的 id)
    private Long emailAccountId; // 冗余字段，方便查询属于哪个账号

    private String subject;      // 标题
    private String sender;       // 发件人名称
    private String address;      // 发件人邮箱地址
    private String sentDate;     // 发送时间 (字符串格式)

    @Lob // 大文本，用于存邮件正文HTML
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    public LocalEmail() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }
    public Long getEmailAccountId() { return emailAccountId; }
    public void setEmailAccountId(Long emailAccountId) { this.emailAccountId = emailAccountId; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getSentDate() { return sentDate; }
    public void setSentDate(String sentDate) { this.sentDate = sentDate; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}