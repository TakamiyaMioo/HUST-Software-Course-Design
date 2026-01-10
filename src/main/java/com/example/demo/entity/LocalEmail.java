package com.example.demo.entity;

import jakarta.persistence.*;

/**
 * 本地邮件实体类 (LocalEmail)
 * * 作用：用于将邮件内容持久化存储到本地数据库中。
 * * 场景：当用户将邮件从“收件箱”移动到“自定义文件夹”时，或者进行邮件归档时，
 * 我们会将邮件内容保存到这张表，而不是仅仅依赖远程 IMAP/POP3 服务。
 */
@Entity
@Table(name = "local_emails") // 显式指定表名。避免使用 "email" 等可能与数据库保留关键字冲突的名称
public class LocalEmail {

    /**
     * 主键 ID
     * 自增策略
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属本地文件夹 ID
     * 标记这封邮件存储在哪个自定义文件夹下。
     */
    private Long folderId;

    /**
     * 所属邮箱账号 ID
     * * 冗余设计：方便快速查询某个邮箱账号下下载了哪些邮件。
     * * 例如：查询属于 "123@qq.com" 的所有本地存档邮件。
     */
    private Long emailAccountId;

    private String subject;      // 邮件标题
    private String sender;       // 发件人名称 (显示名，如 "支付宝")
    private String address;      // 发件人地址 (如 "admin@alipay.com")
    private String sentDate;     // 发送时间 (字符串格式)

    /**
     * 邮件正文 (核心字段)
     * * @Lob: 标记为大对象 (Large Object)。
     * * columnDefinition = "LONGTEXT":
     * MySQL 的默认 String 映射为 VARCHAR(255)，存不下正文。
     * 普通的 TEXT 类型最大仅支持 64KB，稍微复杂点的 HTML 邮件就会超长报错。
     * 这里强制指定为 LONGTEXT (最大 4GB)，确保能存下包含 Base64 图片的长邮件。
     */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /**
     * 无参构造函数
     */
    public LocalEmail() {}

    // ================== Getters and Setters ==================

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