package com.example.demo.entity;

import jakarta.persistence.*;
import java.util.Date;

@Entity
public class DraftEmail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sender;   // 谁写的草稿
    private String receiver; // 收件人（可能为空）
    private String title;    // 主题
    
    @Column(columnDefinition = "TEXT")
    private String content;  // 内容

    private Date saveTime;   // 保存时间

    public DraftEmail() {}

    public DraftEmail(String sender, String receiver, String title, String content) {
        this.sender = sender;
        this.receiver = receiver;
        this.title = title;
        this.content = content;
        this.saveTime = new Date();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Date getSaveTime() { return saveTime; }
    public void setSaveTime(Date saveTime) { this.saveTime = saveTime; }
}