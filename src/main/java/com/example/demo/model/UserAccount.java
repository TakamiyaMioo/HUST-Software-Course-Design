package com.example.demo.model;

public class UserAccount {
    private String email;
    private String password;
    private String type;

    public UserAccount(String email, String password, String type) {
        this.email = email;
        this.password = password;
        this.type = type;
    }

    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public String getType() { return type; }

    public String getSmtpHost() {
        return "qq".equals(type) ? "smtp.qq.com" : "smtp.163.com";
    }

    public String getPop3Host() {
        return "qq".equals(type) ? "pop.qq.com" : "pop.163.com";
    }

    public int getSmtpPort() {
        return "qq".equals(type) ? 465 : 25;
    }
    //

    // 1. 获取 IMAP 服务器地址
    public String getImapHost() {
        // QQ 的是 imap.qq.com，163 的是 imap.163.com
        return "qq".equals(type) ? "imap.qq.com" : "imap.163.com";
    }

    // 2. 获取 IMAP 端口 (SSL加密通常是 993)
    public int getImapPort() {
        return 993;
    }
}

