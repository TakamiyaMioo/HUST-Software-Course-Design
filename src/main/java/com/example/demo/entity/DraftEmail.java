package com.example.demo.entity;

import jakarta.persistence.*;
import java.util.Date;

/**
 * 草稿邮件实体类 (DraftEmail)
 * 对应数据库中的 draft_email 表。
 * 用于存储用户编写但尚未发送的邮件内容。
 */
@Entity
public class DraftEmail {

    /**
     * 主键 ID
     * 自增策略
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 发件人邮箱地址
     * 仅用于显示，方便用户知道是哪个账号存的草稿
     */
    private String sender;

    /**
     * 收件人邮箱地址
     */
    private String receiver;

    /**
     * 邮件标题
     */
    private String title;

    /**
     * 邮件正文
     * @Column(columnDefinition = "TEXT"):
     * 这是一个非常重要的注解。数据库默认的 String 类型（VARCHAR）通常限制 255 个字符。
     * 指定为 TEXT 类型后，可以存储大段的文本（例如几千字的长邮件）。
     */
    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 保存时间
     * 用于在列表页按时间排序显示
     */
    private Date saveTime;

    /**
     * 【新增】归属邮箱账号 ID
     * 标记这个草稿属于哪个 EmailAccount。
     * 用于多账号隔离：如果你绑定了两个邮箱，A邮箱的草稿不应该出现在B邮箱的草稿箱里。
     */
    private Long emailAccountId;

    /**
     * 无参构造函数 (JPA 必须)
     */
    public DraftEmail() {}

    /**
     * 全参构造函数 (推荐使用)
     * 创建草稿时自动记录当前时间
     */
    public DraftEmail(String sender, String receiver, String title, String content, Long emailAccountId) {
        this.sender = sender;
        this.receiver = receiver;
        this.title = title;
        this.content = content;
        this.saveTime = new Date(); // 自动生成当前时间
        this.emailAccountId = emailAccountId;
    }

    /**
     * 兼容旧代码的构造函数 (不带 emailAccountId)
     * 如果旧代码还在调用这个方法，默认将 accountId 设为 null。
     * 建议随着项目迭代，慢慢把旧代码都改为调用上面的全参构造函数。
     */
    public DraftEmail(String sender, String receiver, String title, String content) {
        // 使用 this 调用上面的全参构造函数，减少代码重复
        this(sender, receiver, title, content, null);
    }

    // ================== Getters and Setters ==================

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

    public Long getEmailAccountId() { return emailAccountId; }
    public void setEmailAccountId(Long emailAccountId) { this.emailAccountId = emailAccountId; }
}