package com.example.demo.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 邮件信息模型类 (EmailInfo)
 * 这是一个非持久化类（没有 @Entity 注解）。
 * 作用：作为数据载体，将解析后的邮件详情传递给前端（Thymeleaf 模板或 JSON 响应）。
 */
public class EmailInfo {

    // 邮件的唯一标识 (如果是远程邮件则是 MessageUID，本地邮件则是数据库 ID)
    private Long id;

    // 邮件标题 (Subject)
    private String title;

    // 发件人显示名称 (例如: "GitHub", "张三")
    private String sender;

    // 发件人具体邮箱地址 (例如: "noreply@github.com")
    // 分开存储是为了前端能模仿 Outlook：列表只显示名字，鼠标悬停显示地址
    private String address;

    // 【新增】完整收件人列表字符串
    // 用于在邮件详情页显示 "收件人: 张三, 李四 <lisi@qq.com>"
    // 同时也用于“回复/回复全部”功能时提取收件人
    private String recipients;

    // 发送时间 (字符串格式，如 "2023-10-27 10:00")
    private String sendDate;

    // 邮件正文 (HTML 或 纯文本)
    private String content;

    // 附件文件名列表
    // 初始化为空列表，防止空指针异常
    private List<String> filenames = new ArrayList<>();

    // ================== 构造函数 ==================

    /**
     * 1. 全参构造函数 (用于邮件详情页)
     * 当用户点击查看具体某封邮件时，我们需要展示所有信息，包括详细的收件人列表 (recipients)。
     */
    public EmailInfo(Long id, String title, String sender, String address, String recipients, String sendDate,
                     String content, List<String> filenames) {
        this.id = id;
        this.title = title;
        this.sender = sender;
        this.address = address;
        this.recipients = recipients; // 【新增】这里赋值
        this.sendDate = sendDate;
        this.content = content;
        this.filenames = filenames;
    }

    /**
     * 2. 简易构造函数 (用于邮件列表页)
     * 场景：在收件箱列表中，我们只需要显示标题、发件人和时间，不需要加载正文里的详细收件人列表。
     * 优化：recipients 传 null，减少不必要的解析开销。
     * 实现：通过 this(...) 调用上面的全参构造函数。
     */
    public EmailInfo(Long id, String title, String sender, String address, String sendDate, String content,
                     List<String> filenames) {
        // 第5个参数 recipients 传 null
        this(id, title, sender, address, null, sendDate, content, filenames);
    }

    // ================== Getters ==================
    // 只需要 Getters，因为数据通常是在 Service 层构造时一次性填入的，后续很少修改

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

    // 【新增】获取收件人列表
    public String getRecipients() {
        return recipients;
    }

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