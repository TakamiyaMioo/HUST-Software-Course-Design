package com.example.demo.model;

/**
 * 用户账号会话模型 (UserAccount)
 * * 注意：这不是数据库实体 (@Entity)，不需要存入数据库。
 * * 作用：它是一个“运行时”对象，存放在 HttpSession 中。
 * 包含了连接邮件服务器所需的所有实时信息：明文授权码、服务器地址、端口配置等。
 * Service 层拿到这个对象就可以直接进行网络连接，无需再次查询数据库或解密。
 */
public class UserAccount {
    private String email;    // 邮箱地址
    private String password; // 【关键】这里存储的是解密后的真实授权码/密码
    private String type;     // 邮箱类型标识: "qq", "163", "hust" 等

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
    // 以下方法用于根据邮箱类型 (type) 动态获取服务器配置
    // 这样 Service 层就不需要写死 if-else 判断，直接调用这些方法即可

    /**
     * 获取 SMTP 发送服务器地址
     */
    public String getSmtpHost() {
        if ("qq".equals(type))
            return "smtp.qq.com";
        if ("163".equals(type))
            return "smtp.163.com";
        if ("hust".equals(type))
            return "mail.hust.edu.cn"; // 新增 HUST (华中大邮件服务器)
        return "";
    }

    /**
     * 获取 POP3 接收服务器地址
     */
    public String getPop3Host() {
        if ("qq".equals(type))
            return "pop.qq.com";
        if ("163".equals(type))
            return "pop.163.com";
        if ("hust".equals(type))
            return "mail.hust.edu.cn"; // HUST 的收发服务器地址通常是同一个域名
        return "";
    }

    /**
     * 获取 SMTP 端口号
     * 目前主流邮箱都强制要求 SSL 加密连接
     */
    public int getSmtpPort() {
        // 465 是标准的 SMTP over SSL 端口
        // HUST 邮箱也推荐使用 SSL 加密端口 465
        return 465;
    }

    /**
     * 获取 IMAP 接收服务器地址
     * IMAP 比 POP3 更先进，支持文件夹同步等功能
     */
    public String getImapHost() {
        if ("qq".equals(type))
            return "imap.qq.com";
        if ("163".equals(type))
            return "imap.163.com";
        if ("hust".equals(type))
            return "mail.hust.edu.cn"; // 新增 HUST
        return "";
    }

    /**
     * 获取 IMAP 端口号
     */
    public int getImapPort() {
        // 993 是标准的 IMAP over SSL 端口
        return 993;
    }
    // ==================== 修改结束 ====================
}