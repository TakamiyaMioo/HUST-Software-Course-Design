package com.example.demo.entity;

import jakarta.persistence.*;

/**
 * 文章实体类 (Article)
 * 这是一个简单的实体类，对应数据库中的一张表（通常表名为 article）。
 * 常用于测试或演示目的，只包含最基本的 ID 和 标题信息。
 */
@Entity // 标记这是一个 JPA 实体类，Spring Boot 会自动将其映射到数据库表
public class Article {

    /**
     * 主键 ID
     * @Id: 标记该字段为数据库表的主键
     * @GeneratedValue: 指定主键生成策略
     * strategy = GenerationType.IDENTITY: 表示使用数据库底层的自增列 (Auto Increment)，适用于 MySQL
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 文章标题
     * 对应数据库表中的 title 字段
     */
    private String title;

    /**
     * 无参构造函数
     * JPA 规范强制要求必须有一个无参构造函数，否则从数据库查询数据转为对象时会报错
     */
    public Article() {}

    /**
     * 带参构造函数
     * 方便在 Java 代码中快速创建对象，例如：Article article = new Article("我的第一篇文章");
     */
    public Article(String title) {
        this.title = title;
    }

    // ================== Getters and Setters ==================
    // 用于读取和修改私有字段的方法

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}