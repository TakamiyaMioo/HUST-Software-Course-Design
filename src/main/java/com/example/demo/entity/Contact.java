package com.example.demo.entity;

import jakarta.persistence.*;

/**
 * 联系人实体类 (Contact)
 * 用于存储用户的通讯录数据。
 * 对应数据库中的 contact 表。
 */
@Entity
public class Contact {

    /**
     * 主键 ID
     * 数据库自增策略
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 联系人姓名
     */
    private String name;

    /**
     * 联系人邮箱地址
     */
    private String email;

    /**
     * 【重要新增】归属用户 ID
     * 标记这个联系人属于哪个 AppUser（登录用户）。
     * 用于实现数据隔离，防止用户 A 看到用户 B 的通讯录。
     */
    private Long appUserId;

    /**
     * 无参构造函数
     * JPA 规范必须要求
     */
    public Contact() {}

    /**
     * 全参构造函数
     * 推荐使用此构造函数，明确指定该联系人属于谁。
     * * @param name 姓名
     * @param email 邮箱
     * @param appUserId 所属用户的ID
     */
    public Contact(String name, String email, Long appUserId) {
        this.name = name;
        this.email = email;
        this.appUserId = appUserId;
    }

    /**
     * 旧版本构造函数 (兼容过渡用)
     * 这里的 appUserId 传 null，表示暂时不关联用户。
     * 注意：保留它是为了防止旧代码报错，建议后续修改旧代码后废弃此方法。
     */
    public Contact(String name, String email) {
        // 调用上面的全参构造函数，第三个参数传 null
        this(name, email, null);
    }

    // ================== Getters and Setters ==================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Long getAppUserId() { return appUserId; }
    public void setAppUserId(Long appUserId) { this.appUserId = appUserId; }
}