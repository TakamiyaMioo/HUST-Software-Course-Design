package com.example.demo.model;

import java.util.ArrayList;
import java.util.List;

public class EmailInfo {
    private Long id;
    private String title;
    private String sender;
    private String sendDate;
    private String content;
    // 【新增】附件文件名列表
    private List<String> filenames = new ArrayList<>();

    // 1. 全参构造函数（用于数据库读取，如“已发送”）
    // 注意：这里需要传入 filenames
    public EmailInfo(Long id, String title, String sender, String sendDate, String content, List<String> filenames) {
        this.id = id;
        this.title = title;
        this.sender = sender;
        this.sendDate = sendDate;
        this.content = content;
        this.filenames = filenames;
    }

    // 2. 接收邮件构造函数（用于 POP3 接收，ID 默认为 -1）
    public EmailInfo(String title, String sender, String sendDate, String content, List<String> filenames) {
        this.id = -1L;
        this.title = title;
        this.sender = sender;
        this.sendDate = sendDate;
        this.content = content;
        this.filenames = filenames;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getSender() { return sender; }
    public String getSendDate() { return sendDate; }
    public String getContent() { return content; }
    // 【新增】Getter
    public List<String> getFilenames() { return filenames; }
}