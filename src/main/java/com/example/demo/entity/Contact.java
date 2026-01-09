package com.example.demo.entity;

import jakarta.persistence.*;

@Entity
public class Contact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String email;

    // 新增：归属于哪个主账号
    private Long appUserId;

    public Contact() {}

    // 修改构造函数，暂时允许 appUserId 为空，兼容旧代码，后续会强制要求
    public Contact(String name, String email, Long appUserId) {
        this.name = name;
        this.email = email;
        this.appUserId = appUserId;
    }

    // 保留旧构造函数以防报错，后面会慢慢废弃
    public Contact(String name, String email) {
        this(name, email, null);
    }

    // Getters Setters...
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Long getAppUserId() { return appUserId; }
    public void setAppUserId(Long appUserId) { this.appUserId = appUserId; }
}

//package com.example.demo.entity;
//
//import jakarta.persistence.*;
//
//@Entity
//public class Contact {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    private String name;
//    private String email;
//
//    public Contact() {}
//
//    public Contact(String name, String email) {
//        this.name = name;
//        this.email = email;
//    }
//
//    public Long getId() { return id; }
//    public void setId(Long id) { this.id = id; }
//    public String getName() { return name; }
//    public void setName(String name) { this.name = name; }
//    public String getEmail() { return email; }
//    public void setEmail(String email) { this.email = email; }
//}
