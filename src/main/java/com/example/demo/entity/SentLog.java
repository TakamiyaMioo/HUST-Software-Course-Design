package com.example.demo.entity;

import jakarta.persistence.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 发送日志实体类 (SentLog)
 * 对应数据库中的 sent_log 表。
 * 作用：记录用户通过本系统发送出去的邮件副本，用于在“已发送”箱中显示。
 */
@Entity
public class SentLog {

    /**
     * 主键 ID
     * 数据库自增
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
     * 这是一个关键设置。如果不加这个注解，Hibernate/JPA 默认会将其映射为 VARCHAR(255)。
     * 邮件正文通常都很长（包含 HTML 标签、样式等），必须使用 TEXT 或 LONGTEXT 类型，
     * 否则发送长邮件时数据库会报错 "Data too long"。
     */
    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 发送时间
     * 这里直接存储格式化后的字符串 (例如 "2023-10-27 10:30")，
     * 方便前端直接展示，无需再次格式化。
     */
    private String sendTime;

    /**
     * 【新增】归属邮箱账号 ID
     * 标记这封邮件是用哪个邮箱账号发送的。
     * 场景：用户绑定了 QQ 和 163 两个邮箱。用 QQ 发的邮件，只应该出现在 QQ 账号的“已发送”里。
     */
    private Long emailAccountId;

    /**
     * 无参构造函数 (JPA 规范必须)
     */
    public SentLog() {}

    /**
     * 全参构造函数 (推荐使用)
     * 在创建对象时，自动获取当前系统时间并格式化。
     * @param receiver 收件人
     * @param title 标题
     * @param content 正文
     * @param emailAccountId 发送账号的ID
     */
    public SentLog(String receiver, String title, String content, Long emailAccountId) {
        this.receiver = receiver;
        this.title = title;
        this.content = content;
        // 获取当前时间并格式化为 "年-月-日 时:分"
        this.sendTime = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
        this.emailAccountId = emailAccountId;
    }

    /**
     * 兼容旧构造函数 (不带 emailAccountId)
     * 用于防止旧代码报错。
     * 如果调用此方法，emailAccountId 默认为 null（即未归类）。
     */
    public SentLog(String receiver, String title, String content) {
        this(receiver, title, content, null);
    }

    // ================== Getters and Setters ==================

    public String getReceiver() { return receiver; }
    // 注意：通常实体类也需要 Setter，虽然这里只有 Getter，但 JPA 反射赋值时可能需要
    // 如果后续发现无法修改数据，请补上 setReceiver 等方法

    public String getTitle() { return title; }

    public String getContent() { return content; }

    public String getSendTime() { return sendTime; }

    public Long getId() { return id; }

    public Long getEmailAccountId() { return emailAccountId; }
    public void setEmailAccountId(Long emailAccountId) { this.emailAccountId = emailAccountId; }
}