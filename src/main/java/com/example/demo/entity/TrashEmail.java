package com.example.demo.entity;

import jakarta.persistence.*;

/**
 * 垃圾箱邮件实体类 (TrashEmail)
 * 对应数据库中的 trash_email 表。
 * 用于存储被用户删除的邮件记录。
 * * 注意：当前设计只存储了简单的元数据（发件人、标题、时间），
 * 如果需要实现“从垃圾箱还原邮件”的功能，建议后续加上 content (正文) 字段和 emailAccountId (归属账号)。
 */
@Entity
public class TrashEmail {

    /**
     * 主键 ID
     * 数据库自增
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 发件人
     * 记录这封被删除的邮件是谁发的
     */
    private String sender;

    /**
     * 邮件标题
     */
    private String title;

    /**
     * 发送时间
     * 记录原邮件的发送时间，用于排序
     */
    private String sendTime;

    /**
     * 无参构造函数
     * JPA 规范必须要求
     */
    public TrashEmail() {}

    /**
     * 全参构造函数
     * 用于将邮件移动到垃圾箱时创建记录
     */
    public TrashEmail(String sender, String title, String sendTime) {
        this.sender = sender;
        this.title = title;
        this.sendTime = sendTime;
    }

    // ================== Getters ==================
    // 目前只有 Getter，表示垃圾箱里的记录通常是只读的，或者是创建后就不再修改

    public Long getId() { return id; }

    public String getSender() { return sender; }

    public String getTitle() { return title; }

    public String getSendTime() { return sendTime; }

    // 如果后续需要修改（例如补全数据），建议手动添加 Setters
}