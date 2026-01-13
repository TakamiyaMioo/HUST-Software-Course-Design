# Software-Course-Design

这是一个基于 **Spring Boot** 和 **JavaFX** 开发的混合架构（Hybrid）桌面邮件客户端。它结合了 Web 开发的高效性（Thymeleaf + Spring MVC）和桌面应用的本地体验（JavaFX WebView），邮件收发基于imap、SMTP协议，旨在提供一个轻量、安全且功能丰富的邮件管理解决方案。

软件功能设计参考outlook。

> **项目背景**：本项目最初作为软件工程课程设计开发，涵盖了 SMTP/IMAP 协议集成、多线程网络编程、数据库设计以及前后端混合开发等核心技术点。

------

## 功能特性 (Features)

### 核心邮件功能

- **邮件接收 (IMAP)**：支持分页拉取邮件列表，利用 FetchProfile 预加载技术实现秒级加载。
- **邮件发送 (SMTP)**：支持富文本（HTML）编辑、多收件人群发。
- **附件管理**：支持发送带附件的邮件，以及接收并下载邮件附件到本地。
- **邮件操作**：支持邮件转发（带原附件）、回复、删除（移动到垃圾箱）等操作。
- **多文件夹管理**：自动识别收件箱、已发送、草稿箱、垃圾箱，并支持自定义文件夹的创建与管理。

### 账户与安全

- **多账号管理**：支持同时绑定多个邮箱（QQ、163、HUST的edu邮箱），绑定需要授权码，一键切换当前激活账号。
- **安全存储**：邮箱授权码使用 AES 加密存储，保障用户信息安全。
- **个性化设置**：支持个性化主题，一键换肤。

### 桌面端体验 (Technical Highlights)

- **混合架构**：使用 JavaFX `WebView` 封装 Spring Boot Web 项目，实现 .exe 般的运行体验。
- **原生交互**：
  - 重写了 WebView 的监听器，拦截下载请求并调用系统原生的文件保存对话框。
  - 将 JavaScript 的 `alert/confirm` 映射为 JavaFX 的原生弹窗。
- **协议兼容**：
  - 解决了网易 163 邮箱强制要求 IMAP ID 验证导致的 "Unsafe Login" 问题。
  - 实现了不同邮件服务商文件夹名称（如 "Sent" vs "Sent Messages"）的自动映射。

------

## 技术栈 (Tech Stack)

- **后端核心**：Java 17, Spring Boot 3.3.5, Spring Data JPA
- **邮件协议**：Jakarta Mail (JavaMail)
- **前端视图**：Thymeleaf, HTML5, CSS3
- **桌面容器**：JavaFX (WebView, WebEngine)
- **数据库**：MySQL 8.0 (或其他兼容 JDBC 的数据库)
- **工具库**：Maven, Lombok

------

## 项目结构 (Project Structure)

Plaintext

```
com.example.demo
├── controller      // Web 层：处理 HTTP 请求 (HelloController)
├── service         // 业务层：邮件收发核心逻辑 (MailService)
├── repository      // 持久层：数据库交互 (JPA)
├── entity          // 实体类：数据库表映射 (AppUser, EmailAccount, etc.)
├── model           // 模型类：前端交互数据对象 (EmailInfo)
├── utils           // 工具类：加密工具等 (AESUtil)
├── DesktopLauncher.java // JavaFX 启动器 (WebView 容器)
├── Startup.java         // 程序入口 (解决 JavaFX 模块化启动问题)
└── DemoApplication.java // Spring Boot 启动类
```

------

## 快速开始 (Getting Started)

### 环境准备

- JDK 17 或更高版本
- Maven 3.6+
- MySQL 数据库

### 数据库配置

在 MySQL 中创建一个数据库（例如 `jmail_db`），并修改 `src/main/resources/application.properties`：

Properties

```
# Database Configuration
spring.datasource.url=jdbc:mysql://localhost:3306/jmail_db?useSSL=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=your_password
spring.jpa.hibernate.ddl-auto=update

# File Upload
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

### 运行项目

由于项目使用了 JavaFX，为了避免 JDK 9+ 的模块化路径问题，请务必通过 **`Startup.java`** 类启动项目，而不是 `DesktopLauncher` 或 `DemoApplication`。

**在 IDEA 中运行：**

1. 找到 `com.example.demo.Startup` 类。
2. 右键点击 `Run 'Startup.main()'`。

**或者使用 Maven 运行：**

Bash

```
mvn spring-boot:run
```

### 体验流程

1. 启动后会自动弹出桌面窗口。
2. 注册一个新账户并登录。
3. 进入“设置”页面，点击“添加邮箱”。
   - **QQ邮箱**：请前往 QQ 邮箱设置开启 POP3/IMAP/SMTP 服务，并获取**授权码**（不是QQ密码）。
   - **163邮箱**：同上，使用授权码登录。
4. 添加成功后，系统会自动跳转至收件箱。

------

## 开发注意事项 (Dev Notes)

1. **关于启动类**：`Startup.java` 是一个“伪”入口类，它的存在是为了欺骗 JVM，使其在非模块化路径下正确加载 JavaFX 组件。
2. **本地存储路径**：附件和头像默认保存在项目运行目录下的 `data/` 和 `D:/email_data/` (Service中配置) 目录。在 Linux 环境部署时请注意修改 `MailService.SAVE_PATH` 常量。
3. **WebView 下载**：Web 页面的下载链接（附件导出）是由 `DesktopLauncher` 中的监听器拦截并处理的，如果在纯浏览器中访问 `localhost:端口`，下载体验可能会有所不同。

------

## 贡献 (Contributing)

欢迎提交 Issue 或 Pull Request！

如果你有更好的 UI 设计或发现了新的 Bug，请随时联系我。

------

## 开源协议 (License)

本项目采用 [MIT License](https://www.google.com/search?q=LICENSE) 开源协议。

------

## 截图展示 (Screenshots)

> **登录页**

> **收件箱**

------

Author: [TakamiyaMioo、Yet-yong、Ky0us2ke]

Date: 2026