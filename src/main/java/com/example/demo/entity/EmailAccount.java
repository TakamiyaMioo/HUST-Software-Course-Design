package com.example.demo.entity;

import jakarta.persistence.*;

/**
 * 外部邮箱账号实体类 (EmailAccount)
 * 用于存储用户绑定的第三方邮箱信息（如 QQ邮箱、163邮箱）。
 * 对应数据库中的 email_account 表。
 */
@Entity
public class EmailAccount {

    /**
     * 主键 ID
     * 自增策略
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 邮箱地址
     * 例如: test@qq.com
     */
    private String email;

    /**
     * 邮箱授权码/密码
     * 【重要】出于安全考虑，存入数据库前必须进行加密（使用 AESUtil）。
     * 取出使用时再解密。通常是指第三方邮箱开启 POP3/IMAP 服务时生成的专用授权码，而非网页登录密码。
     */
    private String password;

    /**
     * 邮箱类型
     * 例如: "qq", "163", "gmail"
     * 后端 Service 根据这个字段来决定连接哪个 SMTP/IMAP 服务器地址。
     */
    private String type;

    /**
     * 所属的主账号 ID (外键关联)
     * 标记这个邮箱属于哪个 AppUser。
     * 例如 AppUser(id=1) 是张三，他可以绑定多条 EmailAccount 数据，这里的 appUserId 都会存 1。
     */
    private Long appUserId;

    /**
     * 无参构造函数 (JPA 规范必须)
     */
    public EmailAccount() {}

    /**
     * 全参构造函数
     * 用于在绑定新邮箱时快速创建对象
     */
    public EmailAccount(String email, String password, String type, Long appUserId) {
        this.email = email;
        this.password = password;
        this.type = type;
        this.appUserId = appUserId;
    }

    // ================== Getters and Setters ==================
    // 标准的属性读写方法

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getAppUserId() { return appUserId; }
    public void setAppUserId(Long appUserId) { this.appUserId = appUserId; }
}