//
package com.example.demo.service;

import com.example.demo.model.EmailInfo;
import com.example.demo.model.UserAccount;
import jakarta.mail.*;
import jakarta.mail.internet.MimeUtility;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class MailService {

    public static final String SAVE_PATH = "D:/email_data/";

    /**
     * 1. 核心接收方法 (适配新构造函数)
     */
    public Map<String, Object> receiveEmails(UserAccount user, String folderName, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        List<EmailInfo> emailList = new ArrayList<>();
        Store store = null;
        Folder folder = null;
        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.host", user.getImapHost());
            props.put("mail.imap.port", "993");
            props.put("mail.imap.ssl.enable", "true");
            props.put("mail.imap.partialfetch", "false");

            Session session = Session.getInstance(props);
            store = session.getStore("imap");
            store.connect(user.getImapHost(), user.getEmail(), user.getPassword());

            String realFolder = getCorrectFolderName(user.getType(), folderName);
            folder = store.getFolder(realFolder);
            if (!folder.exists())
                folder = store.getFolder("INBOX");

            folder.open(Folder.READ_ONLY);

            int totalMessages = folder.getMessageCount();
            result.put("totalCount", totalMessages);
            int end = totalMessages - (page - 1) * size;
            int start = end - size + 1;

            if (end > 0) {
                if (start < 1)
                    start = 1;
                Message[] messages = folder.getMessages(start, end);

                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, fp);

                UIDFolder uidFolder = (UIDFolder) folder;
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                boolean isSentFolder = realFolder.equalsIgnoreCase("Sent Messages") || realFolder.equals("已发送");

                for (Message msg : messages) {
                    try {
                        long uid = uidFolder.getUID(msg);
                        String subject = (msg.getSubject() != null) ? MimeUtility.decodeText(msg.getSubject()) : "无标题";

                        String fullString = "";
                        if (isSentFolder) {
                            Address[] recipients = msg.getRecipients(Message.RecipientType.TO);
                            if (recipients != null && recipients.length > 0) {
                                fullString = MimeUtility.decodeText(recipients[0].toString());
                            } else {
                                fullString = "未知收件人";
                            }
                        } else {
                            if (msg.getFrom() != null && msg.getFrom().length > 0) {
                                fullString = MimeUtility.decodeText(msg.getFrom()[0].toString());
                            } else {
                                fullString = "未知发件人";
                            }
                        }

                        String displayName = fullString;
                        String address = "";
                        if (fullString.contains("<")) {
                            displayName = fullString.substring(0, fullString.indexOf("<")).trim();
                            address = fullString.substring(fullString.indexOf("<") + 1, fullString.indexOf(">")).trim();
                            if (displayName.isEmpty())
                                displayName = address;
                        } else {
                            address = fullString;
                        }

                        String sentDate = (msg.getSentDate() != null) ? fmt.format(msg.getSentDate()) : "未知时间";

                        // 使用更新后的构造函数 (列表页recipients传null)
                        emailList.add(
                                new EmailInfo(uid, subject, displayName, address, sentDate, null, new ArrayList<>()));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closeQuietly(folder, store);
        }

        Collections.sort(emailList, (o1, o2) -> o2.getSendDate().compareTo(o1.getSendDate()));
        result.put("list", emailList);
        return result;
    }

    /**
     * 2. 获取邮件详情 (已修改：解析完整的 Recipients 列表)
     */
    public EmailInfo getEmailDetail(UserAccount user, String folderName, long uid) {
        Store store = null;
        Folder folder = null;
        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.host", user.getImapHost());
            props.put("mail.imap.port", "993");
            props.put("mail.imap.ssl.enable", "true");

            Session session = Session.getInstance(props);
            store = session.getStore("imap");
            store.connect(user.getImapHost(), user.getEmail(), user.getPassword());

            String realFolder = getCorrectFolderName(user.getType(), folderName);
            folder = store.getFolder(realFolder);
            folder.open(Folder.READ_ONLY);

            UIDFolder uidFolder = (UIDFolder) folder;
            Message msg = uidFolder.getMessageByUID(uid);
            if (msg == null)
                return null;

            String subject = (msg.getSubject() != null) ? MimeUtility.decodeText(msg.getSubject()) : "无标题";

            // 解析发件人
            String fromFull = "未知";
            if (msg.getFrom() != null && msg.getFrom().length > 0) {
                fromFull = MimeUtility.decodeText(msg.getFrom()[0].toString());
            }
            String fromName = fromFull;
            String fromAddress = "";
            if (fromFull.contains("<")) {
                fromName = fromFull.substring(0, fromFull.indexOf("<")).trim();
                fromAddress = fromFull.substring(fromFull.indexOf("<") + 1, fromFull.indexOf(">")).trim();
                if (fromName.isEmpty())
                    fromName = fromAddress;
            } else {
                fromAddress = fromFull;
            }

            // 【核心修改】解析收件人列表 (To) 用于引用头
            StringBuilder recipientsBuilder = new StringBuilder();
            Address[] recipients = msg.getRecipients(Message.RecipientType.TO);
            if (recipients != null) {
                for (int i = 0; i < recipients.length; i++) {
                    recipientsBuilder.append(MimeUtility.decodeText(recipients[i].toString()));
                    if (i < recipients.length - 1)
                        recipientsBuilder.append("; ");
                }
            }
            String recipientsStr = recipientsBuilder.toString();
            if (recipientsStr.isEmpty())
                recipientsStr = "未知";

            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            String sentDate = (msg.getSentDate() != null) ? fmt.format(msg.getSentDate()) : "未知时间";

            StringBuilder contentBuffer = new StringBuilder();
            List<String> attachmentList = new ArrayList<>();
            parseMessage(msg, contentBuffer, attachmentList);

            // 传入 recipientsStr
            return new EmailInfo(uid, subject, fromName, fromAddress, recipientsStr, sentDate, contentBuffer.toString(),
                    attachmentList);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            closeQuietly(folder, store);
        }
    }

    /**
     * 3. 移动邮件到“已删除” (同步操作)
     * 逻辑：Copy 到服务器的删除文件夹 -> 标记原邮件为 DELETED
     */
    public void moveToTrash(UserAccount user, String fromFolderName, long uid) {
        Store store = null;
        Folder sourceFolder = null;
        Folder trashFolder = null;
        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.host", user.getImapHost());
            props.put("mail.imap.port", "993");
            props.put("mail.imap.ssl.enable", "true");

            Session session = Session.getInstance(props);
            store = session.getStore("imap");
            store.connect(user.getImapHost(), user.getEmail(), user.getPassword());

            // 打开源文件夹
            String sourceRealName = getCorrectFolderName(user.getType(), fromFolderName);
            sourceFolder = store.getFolder(sourceRealName);
            sourceFolder.open(Folder.READ_WRITE);

            // 打开目标文件夹 (已删除)
            String trashRealName = getCorrectFolderName(user.getType(), "已删除");
            trashFolder = store.getFolder(trashRealName);

            // 容错兜底：防止服务器文件夹名字不对
            if (!trashFolder.exists()) {
                if ("qq".equals(user.getType()))
                    trashFolder = store.getFolder("Deleted Messages");
                else
                    trashFolder = store.getFolder("已删除");
            }

            if (trashFolder.exists()) {
                trashFolder.open(Folder.READ_WRITE);
            }

            UIDFolder uidFolder = (UIDFolder) sourceFolder;
            Message msg = uidFolder.getMessageByUID(uid);

            if (msg != null) {
                // 只有当“已删除”文件夹存在时才复制
                if (trashFolder.exists() && trashFolder.isOpen()) {
                    sourceFolder.copyMessages(new Message[] { msg }, trashFolder);
                }
                // 标记删除
                msg.setFlag(Flags.Flag.DELETED, true);
            }

            sourceFolder.expunge(); // 物理清除

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closeQuietly(trashFolder, null);
            closeQuietly(sourceFolder, store);
        }
    }

    /**
     * 4. 彻底删除邮件 (同步操作)
     */
    public void deleteMessage(UserAccount user, String folderName, long uid) {
        Store store = null;
        Folder folder = null;
        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.host", user.getImapHost());
            props.put("mail.imap.port", "993");
            props.put("mail.imap.ssl.enable", "true");

            Session session = Session.getInstance(props);
            store = session.getStore("imap");
            store.connect(user.getImapHost(), user.getEmail(), user.getPassword());

            String realFolder = getCorrectFolderName(user.getType(), folderName);
            folder = store.getFolder(realFolder);
            folder.open(Folder.READ_WRITE);

            UIDFolder uidFolder = (UIDFolder) folder;
            Message msg = uidFolder.getMessageByUID(uid);

            if (msg != null) {
                msg.setFlag(Flags.Flag.DELETED, true);
            }
            folder.expunge(); // 物理删除

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closeQuietly(folder, store);
        }
    }

    /**
     * 5. 发送邮件 + 同步保存到已发送
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

            sender.send(message); // SMTP 发送

            // 同步到 IMAP 已发送
            saveToSentFolder(user, message);

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("发送失败：" + e.getMessage());
        }
    }

    // --- 辅助方法 ---

    private void saveToSentFolder(UserAccount user, jakarta.mail.internet.MimeMessage message) {
        Store store = null;
        Folder sentFolder = null;
        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.host", user.getImapHost());
            props.put("mail.imap.port", "993");
            props.put("mail.imap.ssl.enable", "true");

            Session session = Session.getInstance(props);
            store = session.getStore("imap");
            store.connect(user.getImapHost(), user.getEmail(), user.getPassword());

            String sentName = getCorrectFolderName(user.getType(), "已发送");
            sentFolder = store.getFolder(sentName);
            if (!sentFolder.exists())
                sentFolder = store.getFolder("Sent Messages");

            if (sentFolder.exists()) {
                sentFolder.open(Folder.READ_WRITE);
                message.setFlag(Flags.Flag.SEEN, true);
                sentFolder.appendMessages(new Message[] { message });
            }
        } catch (Exception e) {
            System.err.println("同步已发送失败: " + e.getMessage());
        } finally {
            closeQuietly(sentFolder, store);
        }
    }

    // 【核心修正】文件夹名称映射
    // "已删除" -> QQ: "Deleted Messages" / 163: "已删除"
    private String getCorrectFolderName(String mailType, String uiFolderName) {
        if ("收件箱".equals(uiFolderName))
            return "INBOX";

        if ("qq".equals(mailType)) {
            if ("已发送".equals(uiFolderName))
                return "Sent Messages";
            // 关键：QQ 的删除文件夹通常叫 Deleted Messages
            if ("已删除".equals(uiFolderName) || "垃圾箱".equals(uiFolderName))
                return "Deleted Messages";
        }

        if ("163".equals(mailType)) {
            if ("已发送".equals(uiFolderName))
                return "已发送";
            // 163 的删除文件夹叫 已删除
            if ("已删除".equals(uiFolderName) || "垃圾箱".equals(uiFolderName))
                return "已删除";
        }
        return uiFolderName;
    }

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
        }
        return sender;
    }

    private void parseMessage(Part part, StringBuilder bodyText, List<String> attachments) throws Exception {
        if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
            bodyText.append(part.getContent().toString());
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                parseMessage(multipart.getBodyPart(i), bodyText, attachments);
            }
        } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) ||
                (part.getFileName() != null && !part.getFileName().isEmpty())) {
            String fileName = MimeUtility.decodeText(part.getFileName());
            File saveDir = new File(SAVE_PATH);
            if (!saveDir.exists())
                saveDir.mkdirs();
            try (InputStream is = part.getInputStream()) {
                Files.copy(is, new File(SAVE_PATH + fileName).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            attachments.add(fileName);
        }
    }

    private void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen())
                folder.close(false);
        } catch (Exception e) {
        }
        try {
            if (store != null)
                store.close();
        } catch (Exception e) {
        }

    }
    //

    /**
     * 【修改后】直接转发邮件 (支持转发原邮件的所有附件)
     */
    public void forwardMail(UserAccount user, String folder, Long originalUid, String targetEmail, String userComment) {
        try {
            // 1. 获取原邮件详情 (此时附件会被下载到 SAVE_PATH)
            EmailInfo original = getEmailDetail(user, folder, originalUid);
            if (original == null)
                throw new RuntimeException("原邮件加载失败");

            // 2. 构建主题
            String subject = "Fwd: " + original.getTitle();

            // 3. 构建正文 (留言 + 引用)
            StringBuilder contentBuilder = new StringBuilder();
            if (userComment != null && !userComment.isEmpty()) {
                contentBuilder.append("<div style='margin-bottom: 20px; font-size: 14px;'>")
                        .append(userComment.replace("\n", "<br>"))
                        .append("</div>");
            }

            String senderStr = original.getSender();
            if (original.getAddress() != null && !original.getAddress().isEmpty()) {
                senderStr += " &lt;" + original.getAddress() + "&gt;";
            }

            String recipientsStr = original.getRecipients();
            if (recipientsStr != null)
                recipientsStr = recipientsStr.replace("<", "&lt;").replace(">", "&gt;");

            String quoteHeader = "<div style='background:#f2f2f2; padding:10px; font-size:12px; color:#333; line-height:1.6; border-radius:5px;'>"
                    +
                    "<div>------------------ 原始邮件 ------------------</div>" +
                    "<div><b>发件人:</b> " + senderStr + "</div>" +
                    "<div><b>发送时间:</b> " + original.getSendDate() + "</div>" +
                    "<div><b>收件人:</b> " + (recipientsStr != null ? recipientsStr : "") + "</div>" +
                    "<div><b>主题:</b> " + original.getTitle() + "</div>" +
                    "</div><br>";

            contentBuilder.append(quoteHeader);
            contentBuilder.append(original.getContent());

            // 4. 【核心修改】构建邮件发送器
            JavaMailSenderImpl sender = createSender(user);
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            // true 表示支持 multipart (附件)
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(
                    message, true, "UTF-8");

            helper.setFrom(user.getEmail());
            helper.setTo(targetEmail);
            helper.setSubject(subject);
            helper.setText(contentBuilder.toString(), true);

            // 5. 【核心修改】遍历并添加本地附件
            List<String> filenames = original.getFilenames();
            if (filenames != null && !filenames.isEmpty()) {
                for (String filename : filenames) {
                    File file = new File(SAVE_PATH + filename);
                    if (file.exists()) {
                        // 添加附件到邮件
                        helper.addAttachment(filename, file);
                        System.out.println("✅ 已添加附件: " + filename);
                    }
                }
            }

            // 6. 发送并保存到已发送
            sender.send(message);
            saveToSentFolder(user, message);

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("转发失败: " + e.getMessage());
        }
    }
}