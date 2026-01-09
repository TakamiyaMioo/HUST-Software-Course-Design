package com.example.demo.model;

public class UserAccount {
    private String email;
    private String password;
    private String type; // "qq", "163", "hust"

    public UserAccount(String email, String password, String type) {
        this.email = email;
        this.password = password;
        this.type = type;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    public String getType() {
        return type;
    }

    // ==================== 修改开始 ====================

    public String getSmtpHost() {
        if ("qq".equals(type))
            return "smtp.qq.com";
        if ("163".equals(type))
            return "smtp.163.com";
        if ("hust".equals(type))
            return "mail.hust.edu.cn"; // 新增 HUST
        return "";
    }

    public String getPop3Host() {
        if ("qq".equals(type))
            return "pop.qq.com";
        if ("163".equals(type))
            return "pop.163.com";
        if ("hust".equals(type))
            return "mail.hust.edu.cn"; // 新增 HUST
        return "";
    }

    public int getSmtpPort() {
        // HUST 邮箱推荐使用 SSL 加密端口 465
        return 465;
    }

    public String getImapHost() {
        if ("qq".equals(type))
            return "imap.qq.com";
        if ("163".equals(type))
            return "imap.163.com";
        if ("hust".equals(type))
            return "mail.hust.edu.cn"; // 新增 HUST
        return "";
    }

    public int getImapPort() {
        // SSL 加密端口
        return 993;
    }
    // ==================== 修改结束 ====================
}