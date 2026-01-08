package com.example.demo.service;

import com.example.demo.model.EmailInfo;
import com.example.demo.model.UserAccount;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeUtility;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;


@Service
public class MailService {

    // 【配置】附件保存路径 (请确保 D 盘存在，或者改成 C:/email_data/)
    public static final String SAVE_PATH = "D:/email_data/";

    /**
     * 动态获取发送器
     */
    private JavaMailSenderImpl createSender(UserAccount user) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(user.getSmtpHost());
        sender.setPort(user.getSmtpPort());
        sender.setUsername(user.getEmail());
        sender.setPassword(user.getPassword());
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        if ("qq".equals(user.getType())) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.starttls.enable", "true");
        }
        return sender;
    }

    /**
     * 发送邮件
     */
    public void sendMailWithAttachment(UserAccount user, String to, String subject, String content,
            org.springframework.web.multipart.MultipartFile file) {
        try {
            JavaMailSenderImpl sender = createSender(user);
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(
                    message, true, "UTF-8");

            helper.setFrom(user.getEmail());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);

            if (file != null && !file.isEmpty()) {
                helper.addAttachment(file.getOriginalFilename(), file);
            }

            sender.send(message);
            System.out.println("✅ 邮件发送成功！");

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("发送失败：" + e.getMessage());
        }
    }

    /**
     * 【核心】解析邮件内容（递归提取正文 + 保存附件）
     * 修复说明：这里不再使用 part.saveFile，而是用流 (Stream) 的方式保存
     */
    private void parseMessage(Part part, StringBuilder bodyText, List<String> attachments) throws Exception {
        // 1. 如果是附件
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) ||
                (part.getFileName() != null && !part.getFileName().isEmpty())) {

            String fileName = MimeUtility.decodeText(part.getFileName());

            // 自动创建目录
            File saveDir = new File(SAVE_PATH);
            if (!saveDir.exists())
                saveDir.mkdirs();

            // 【修复点】：使用输入流读取附件，并复制到硬盘
            try (InputStream is = part.getInputStream()) {
                File targetFile = new File(SAVE_PATH + fileName);
                Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // 记录文件名
            attachments.add(fileName);
            return;
        }

        // 2. 如果是纯文本
        if (part.isMimeType("text/plain")) {
            bodyText.append(part.getContent().toString());
        }
        // 3. 如果是 HTML
        else if (part.isMimeType("text/html")) {
            bodyText.append(part.getContent().toString());
        }
        // 4. 如果是 Multipart 容器，递归处理
        else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                parseMessage(multipart.getBodyPart(i), bodyText, attachments);
            }
        }
    }

    /**
     * 接收邮件
     */
    //

    // 修改返回类型为 Map<String, Object>，以便同时返回 list 和 totalCount
    public java.util.Map<String, Object> receiveEmails(UserAccount user, int page, int size) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        List<EmailInfo> emailList = new ArrayList<>();

        try {
            Properties props = new Properties();
            props.setProperty("mail.store.protocol", "pop3");
            props.setProperty("mail.pop3.host", user.getPop3Host());
            if ("qq".equals(user.getType())) {
                props.setProperty("mail.pop3.ssl.enable", "true");
                props.setProperty("mail.pop3.port", "995");
            } else {
                props.setProperty("mail.pop3.port", "110");
            }

            Session session = Session.getDefaultInstance(props);
            Store store = session.getStore("pop3");
            store.connect(user.getPop3Host(), user.getEmail(), user.getPassword());

            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);

            // 1. 获取总邮件数
            int totalMessages = folder.getMessageCount();
            result.put("totalCount", totalMessages); // 放入结果集

            // 2. 计算当前页需要的邮件索引范围 (POP3 索引从 1 开始，且 1 是最旧的，total 是最新的)
            // page 1 (size 10): 读取 total ~ total-9
            // page 2 (size 10): 读取 total-10 ~ total-19

            int end = totalMessages - (page - 1) * size;
            int start = end - size + 1;

            // 边界检查
            if (end > 0) {
                if (start < 1)
                    start = 1; // 防止越界

                // getMessages(start, end) 是闭区间，包含 start 和 end
                Message[] messages = folder.getMessages(start, end);
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");

                // 3. 倒序遍历（这样最新的邮件会在 List 的最前面）
                for (int i = messages.length - 1; i >= 0; i--) {
                    Message msg = messages[i];
                    // ... (原有的解析逻辑保持不变) ...
                    String subject = (msg.getSubject() != null) ? MimeUtility.decodeText(msg.getSubject()) : "无标题";

                    String from = "未知";
                    if (msg.getFrom() != null && msg.getFrom().length > 0) {
                        from = MimeUtility.decodeText(msg.getFrom()[0].toString());
                        if (from.contains("<"))
                            from = from.substring(0, from.indexOf("<")).trim();
                    }

                    String sentDate = (msg.getSentDate() != null) ? fmt.format(msg.getSentDate()) : "未知时间";

                    StringBuilder contentBuffer = new StringBuilder();
                    List<String> attachmentList = new ArrayList<>();
                    try {
                        // 调用原有的 parseMessage 解析内容
                        parseMessage(msg, contentBuffer, attachmentList);
                    } catch (Exception e) {
                        contentBuffer.append("（内容解析异常）");
                    }

                    emailList.add(new EmailInfo(subject, from, sentDate, contentBuffer.toString(), attachmentList));
                }
            }

            folder.close(false);
            store.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        result.put("list", emailList);
        return result;
    }
}