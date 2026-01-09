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

    @Column(columnDefinition = "TEXT")
    private String content; // 建议加上 TEXT 类型，防止内容过长报错

    private String sendTime;

    // 新增：归属于哪个邮箱账号ID
    private Long emailAccountId;

    public SentLog() {}

    public SentLog(String receiver, String title, String content, Long emailAccountId) {
        this.receiver = receiver;
        this.title = title;
        this.content = content;
        this.sendTime = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
        this.emailAccountId = emailAccountId;
    }

    // 兼容旧构造函数
    public SentLog(String receiver, String title, String content) {
        this(receiver, title, content, null);
    }

    // Getters Setters...
    public String getReceiver() { return receiver; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getSendTime() { return sendTime; }
    public Long getId() { return id; }
    public Long getEmailAccountId() { return emailAccountId; }
    public void setEmailAccountId(Long emailAccountId) { this.emailAccountId = emailAccountId; }
}

//package com.example.demo.entity;
//
//import jakarta.persistence.*;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//
//@Entity
//public class SentLog {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    private String receiver;
//    private String title;
//    private String content;
//    private String sendTime;
//
//    public SentLog() {}
//
//    public SentLog(String receiver, String title, String content) {
//        this.receiver = receiver;
//        this.title = title;
//        this.content = content;
//        this.sendTime = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
//    }
//
//    public String getReceiver() { return receiver; }
//    public String getTitle() { return title; }
//    public String getContent() { return content; }
//    public String getSendTime() { return sendTime; }
//    public Long getId() {
//        return id;
//    }
//}