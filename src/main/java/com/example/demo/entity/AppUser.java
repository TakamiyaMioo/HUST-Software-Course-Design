package com.example.demo.entity;

import jakarta.persistence.*;

/**
 * 应用用户实体类 (AppUser)
 * * 这个类对应数据库中的一张表，用于存储可以登录本系统的用户信息。
 * 包含了账号、密码、昵称以及头像路径等基础信息。
 */
@Entity // 标记这是一个 JPA 实体，Spring Boot 启动时会自动在数据库创建对应的表
public class AppUser {

    /**
     * 用户 ID (主键)
     * @Id: 标记为主键
     * @GeneratedValue: 指定主键生成策略为自增 (Auto Increment)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户名 (登录账号)
     * unique = true: 数据库层面保证用户名唯一，不可重复
     * nullable = false: 必填项，不能为空
     */
    @Column(unique = true, nullable = false)
    private String username;

    /**
     * 用户密码
     * 实际生产中通常存储加密后的哈希值，这里存储登录软件的密码
     */
    private String password;

    /**
     * 用户昵称
     * 用于在页面右上角或欢迎语中显示的名称
     */
    private String nickname;

    /**
     * 【新增】头像路径
     * 这里存储的不是图片文件本身，而是图片访问的 URL 路径。
     * 例如: "/avatar/show?filename=xxx.jpg" 或 "/images/default.png"
     */
    private String avatar;

    /**
     * 无参构造函数
     * JPA 规范强制要求必须有一个无参构造函数，用于反射创建对象
     */
    public AppUser() {}

    /**
     * 带参构造函数
     * 方便在代码中快速创建新用户对象（例如在注册功能中使用）
     */
    public AppUser(String username, String password, String nickname) {
        this.username = username;
        this.password = password;
        this.nickname = nickname;
    }

    // ================== Getters and Setters ==================
    // 下面的方法用于读取和修改对象的私有属性

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    // 【新增】Avatar 的 Getter/Setter，用于读取和设置头像路径
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
}