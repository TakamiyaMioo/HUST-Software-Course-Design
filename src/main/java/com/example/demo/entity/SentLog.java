package com.example.demo.entity;

import jakarta.persistence.*;
import java.text.SimpleDateFormat;
import java.util.Date;

@Entity
public class SentLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String receiver;
    private String title;
    private String content;
    private String sendTime;

    public SentLog() {}

    public SentLog(String receiver, String title, String content) {
        this.receiver = receiver;
        this.title = title;
        this.content = content;
        this.sendTime = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
    }

    public String getReceiver() { return receiver; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getSendTime() { return sendTime; }
    public Long getId() {
        return id;
    }
}