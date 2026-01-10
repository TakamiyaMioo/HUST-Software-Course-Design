package com.example.demo.entity;

import jakarta.persistence.*;

/**
 * 本地自定义文件夹实体类 (LocalFolder)
 * 对应数据库中的 local_folder 表。
 * 用于存储用户创建的自定义文件夹结构（不包含邮件内容，只存文件夹本身的信息）。
 * 邮件内容会存储在 LocalEmail 表中，并通过 folderId 关联到这里。
 */
@Entity
public class LocalFolder {

    /**
     * 主键 ID
     * 数据库自增
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 文件夹名称
     * 例如: "重要工作", "旅行计划", "账单"
     */
    private String folderName;

    /**
     * 所属邮箱账号 ID (外键关联)
     * * 关键字段：标记这个文件夹属于哪个邮箱账号。
     * * 作用：实现数据隔离。
     * 如果你有两个邮箱账号 A 和 B，你在 A 账号下创建了“工作”文件夹，
     * 切换到 B 账号时是不应该看到这个文件夹的。这个字段就是用来做过滤的。
     */
    private Long emailAccountId;

    /**
     * 无参构造函数
     * JPA 规范要求必须存在
     */
    public LocalFolder() {}

    /**
     * 全参构造函数
     * 用于创建新文件夹时使用
     * @param folderName 文件夹名字
     * @param emailAccountId 归属的邮箱账号ID
     */
    public LocalFolder(String folderName, Long emailAccountId) {
        this.folderName = folderName;
        this.emailAccountId = emailAccountId;
    }

    // ================== Getters and Setters ==================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFolderName() { return folderName; }
    public void setFolderName(String folderName) { this.folderName = folderName; }

    public Long getEmailAccountId() { return emailAccountId; }
    public void setEmailAccountId(Long emailAccountId) { this.emailAccountId = emailAccountId; }
}