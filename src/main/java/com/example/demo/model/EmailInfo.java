//
package com.example.demo.model;

import java.util.ArrayList;
import java.util.List;

public class EmailInfo {
    private Long id;
    private String title;
    private String sender; // 显示名称 (如: 张三)
    private String address; // 邮箱地址 (如: zhangsan@qq.com)
    private String recipients; // 【新增】完整收件人列表 (用于引用头显示)
    private String sendDate;
    private String content;
    private List<String> filenames = new ArrayList<>();

    // 1. 全参构造函数 (更新)
    public EmailInfo(Long id, String title, String sender, String address, String recipients, String sendDate,
            String content, List<String> filenames) {
        this.id = id;
        this.title = title;
        this.sender = sender;
        this.address = address;
        this.recipients = recipients; // 【新增】
        this.sendDate = sendDate;
        this.content = content;
        this.filenames = filenames;
    }

    // 2. 列表用的简易构造函数 (更新，列表页暂时不需要recipients长串，传null即可)
    public EmailInfo(Long id, String title, String sender, String address, String sendDate, String content,
            List<String> filenames) {
        this(id, title, sender, address, null, sendDate, content, filenames);
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSender() {
        return sender;
    }

    public String getAddress() {
        return address;
    }

    public String getRecipients() {
        return recipients;
    } // 【新增】

    public String getSendDate() {
        return sendDate;
    }

    public String getContent() {
        return content;
    }

    public List<String> getFilenames() {
        return filenames;
    }
}