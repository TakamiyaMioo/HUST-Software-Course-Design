package com.example.demo.service;

import com.example.demo.model.EmailInfo;
import com.example.demo.model.UserAccount;
import jakarta.mail.*;
import jakarta.mail.internet.MimeUtility;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MailService {

    public static final String SAVE_PATH = "D:/email_data/";

    /**
     * 核心接收方法 (支持：分页 + 排序 + 定向搜索)
     *
     * @param keyword    搜索关键字
     * @param searchType 搜索类型: "all"(默认), "sender", "title", "date"
     */
    public Map<String, Object> receiveEmails(UserAccount user, String folderName, int page, int size,
                                             String sortField, String sortOrder, String keyword, String searchType) {
        Map<String, Object> result = new HashMap<>();
        List<EmailInfo> fullList = new ArrayList<>();
        Store store = null;
        Folder folder = null;
        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.host", user.getImapHost());
            props.put("mail.imap.port", "993");
            props.put("mail.imap.ssl.enable", "true");
            // 关闭部分抓取，确保获取完整信头用于搜索
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
            result.put("totalCount", totalMessages); // 初始总数

            Message[] messages = null;

            // --- 策略判断 ---
            // 只有当 (无搜索词) 且 (默认排序) 时，才使用极速模式
            boolean hasKeyword = StringUtils.hasText(keyword);
            boolean isDefaultSort = (sortField == null || "date".equals(sortField)) && (sortOrder == null || "desc".equals(sortOrder));
            boolean useServerSidePaging = !hasKeyword && isDefaultSort;

            if (useServerSidePaging) {
                // [极速模式]
                int end = totalMessages - (page - 1) * size;
                int start = end - size + 1;
                if (end > 0) {
                    if (start < 1) start = 1;
                    messages = folder.getMessages(start, end);
                }
            } else {
                // [全量模式]
                if (totalMessages > 0) {
                    messages = folder.getMessages();
                }
            }

            if (messages != null && messages.length > 0) {
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, fp);

                boolean isSentFolder = realFolder.equalsIgnoreCase("Sent Messages") || realFolder.equals("已发送");
                fullList = parseMessages((UIDFolder) folder, messages, isSentFolder);
            } else {
                if (!useServerSidePaging) {
                    result.put("totalCount", 0);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closeQuietly(folder, store);
        }

        // --- 1. 内存搜索过滤 (支持 searchType) ---
        if (StringUtils.hasText(keyword)) {
            String k = keyword.toLowerCase();
            String type = (searchType != null) ? searchType : "all";

            fullList = fullList.stream()
                    .filter(email -> {
                        boolean match = false;
                        switch (type) {
                            case "sender": // 仅搜发件人
                                match = (email.getSender() != null && email.getSender().toLowerCase().contains(k));
                                break;
                            case "title": // 仅搜主题
                                match = (email.getTitle() != null && email.getTitle().toLowerCase().contains(k));
                                break;
                            case "date": // 仅搜日期
                                match = (email.getSendDate() != null && email.getSendDate().contains(k));
                                break;
                            case "all": // 搜全部
                            default:
                                match = (email.getTitle() != null && email.getTitle().toLowerCase().contains(k)) ||
                                        (email.getSender() != null && email.getSender().toLowerCase().contains(k)) ||
                                        (email.getSendDate() != null && email.getSendDate().contains(k));
                                break;
                        }
                        return match;
                    })
                    .collect(Collectors.toList());

            // 更新搜索后的总数
            result.put("totalCount", fullList.size());
        }

        // --- 2. 内存排序 ---
        Comparator<EmailInfo> comparator = null;
        String field = sortField != null ? sortField : "date";

        switch (field) {
            case "sender":
                comparator = Comparator.comparing(EmailInfo::getSender, String.CASE_INSENSITIVE_ORDER);
                break;
            case "title":
                comparator = Comparator.comparing(EmailInfo::getTitle, String.CASE_INSENSITIVE_ORDER);
                break;
            case "date":
            default:
                comparator = Comparator.comparing(EmailInfo::getSendDate);
                break;
        }

        if ("desc".equals(sortOrder)) {
            if (comparator != null) comparator = comparator.reversed();
        }

        if (!fullList.isEmpty() && comparator != null) {
            Collections.sort(fullList, comparator);
        }

        // --- 3. 内存分页 ---
        boolean hasKeyword = StringUtils.hasText(keyword);
        boolean isDefaultSort = (sortField == null || "date".equals(sortField)) && (sortOrder == null || "desc".equals(sortOrder));
        boolean useServerSidePaging = !hasKeyword && isDefaultSort;

        List<EmailInfo> pageList;
        if (useServerSidePaging) {
            pageList = fullList;
        } else {
            int fromIndex = (page - 1) * size;
            if (fromIndex >= fullList.size()) {
                pageList = new ArrayList<>();
            } else {
                int toIndex = Math.min(fromIndex + size, fullList.size());
                pageList = fullList.subList(fromIndex, toIndex);
            }
        }

        result.put("list", pageList);
        return result;
    }

    /**
     * 辅助方法：批量解析 Message 到 EmailInfo
     */
    private List<EmailInfo> parseMessages(UIDFolder uidFolder, Message[] messages, boolean isSentFolder) throws Exception {
        List<EmailInfo> list = new ArrayList<>();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");

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

                list.add(new EmailInfo(uid, subject, displayName, address, sentDate, null, new ArrayList<>()));
            } catch (Exception e) {
                // ignore
            }
        }
        return list;
    }

    // --- 保持其他方法不变 (getEmailDetail, moveToTrash, deleteMessage, sendMail, forwardMail) ---
    // 为节省篇幅，这里假设你已经保留了上个版本中的这些方法代码
    
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
            if (msg == null) return null;
            String subject = (msg.getSubject() != null) ? MimeUtility.decodeText(msg.getSubject()) : "无标题";
            String fromFull = "未知";
            if (msg.getFrom() != null && msg.getFrom().length > 0) {
                fromFull = MimeUtility.decodeText(msg.getFrom()[0].toString());
            }
            String fromName = fromFull;
            String fromAddress = "";
            if (fromFull.contains("<")) {
                fromName = fromFull.substring(0, fromFull.indexOf("<")).trim();
                fromAddress = fromFull.substring(fromFull.indexOf("<") + 1, fromFull.indexOf(">")).trim();
                if (fromName.isEmpty()) fromName = fromAddress;
            } else {
                fromAddress = fromFull;
            }
            StringBuilder recipientsBuilder = new StringBuilder();
            Address[] recipients = msg.getRecipients(Message.RecipientType.TO);
            if (recipients != null) {
                for (int i = 0; i < recipients.length; i++) {
                    recipientsBuilder.append(MimeUtility.decodeText(recipients[i].toString()));
                    if (i < recipients.length - 1) recipientsBuilder.append("; ");
                }
            }
            String recipientsStr = recipientsBuilder.toString();
            if (recipientsStr.isEmpty()) recipientsStr = "未知";
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            String sentDate = (msg.getSentDate() != null) ? fmt.format(msg.getSentDate()) : "未知时间";
            StringBuilder contentBuffer = new StringBuilder();
            List<String> attachmentList = new ArrayList<>();
            parseMessage(msg, contentBuffer, attachmentList);
            return new EmailInfo(uid, subject, fromName, fromAddress, recipientsStr, sentDate, contentBuffer.toString(), attachmentList);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            closeQuietly(folder, store);
        }
    }

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
            String sourceRealName = getCorrectFolderName(user.getType(), fromFolderName);
            sourceFolder = store.getFolder(sourceRealName);
            sourceFolder.open(Folder.READ_WRITE);
            String trashRealName = getCorrectFolderName(user.getType(), "已删除");
            trashFolder = store.getFolder(trashRealName);
            if (!trashFolder.exists()) {
                if ("qq".equals(user.getType())) trashFolder = store.getFolder("Deleted Messages");
                else trashFolder = store.getFolder("已删除");
            }
            if (trashFolder.exists()) {
                trashFolder.open(Folder.READ_WRITE);
            }
            UIDFolder uidFolder = (UIDFolder) sourceFolder;
            Message msg = uidFolder.getMessageByUID(uid);
            if (msg != null) {
                if (trashFolder.exists() && trashFolder.isOpen()) {
                    sourceFolder.copyMessages(new Message[] { msg }, trashFolder);
                }
                msg.setFlag(Flags.Flag.DELETED, true);
            }
            sourceFolder.expunge();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closeQuietly(trashFolder, null);
            closeQuietly(sourceFolder, store);
        }
    }

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
            folder.expunge();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closeQuietly(folder, store);
        }
    }

    public void sendMailWithAttachment(UserAccount user, String to, String subject, String content, org.springframework.web.multipart.MultipartFile file) {
        try {
            JavaMailSenderImpl sender = createSender(user);
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(user.getEmail());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            if (file != null && !file.isEmpty()) {
                helper.addAttachment(file.getOriginalFilename(), file);
            }
            sender.send(message);
            saveToSentFolder(user, message);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("发送失败：" + e.getMessage());
        }
    }

    public void forwardMail(UserAccount user, String folder, Long originalUid, String targetEmail, String userComment) {
        try {
            EmailInfo original = getEmailDetail(user, folder, originalUid);
            if (original == null) throw new RuntimeException("原邮件加载失败");
            String subject = "Fwd: " + original.getTitle();
            StringBuilder contentBuilder = new StringBuilder();
            if (userComment != null && !userComment.isEmpty()) {
                contentBuilder.append("<div style='margin-bottom: 20px; font-size: 14px;'>").append(userComment.replace("\n", "<br>")).append("</div>");
            }
            String senderStr = original.getSender();
            if (original.getAddress() != null && !original.getAddress().isEmpty()) senderStr += " &lt;" + original.getAddress() + "&gt;";
            String recipientsStr = original.getRecipients();
            if (recipientsStr != null) recipientsStr = recipientsStr.replace("<", "&lt;").replace(">", "&gt;");
            String quoteHeader = "<div style='background:#f2f2f2; padding:10px; font-size:12px; color:#333; line-height:1.6; border-radius:5px;'>" +
                    "<div>------------------ 原始邮件 ------------------</div>" +
                    "<div><b>发件人:</b> " + senderStr + "</div>" +
                    "<div><b>发送时间:</b> " + original.getSendDate() + "</div>" +
                    "<div><b>收件人:</b> " + (recipientsStr != null ? recipientsStr : "") + "</div>" +
                    "<div><b>主题:</b> " + original.getTitle() + "</div>" +
                    "</div><br>";
            contentBuilder.append(quoteHeader);
            contentBuilder.append(original.getContent());
            JavaMailSenderImpl sender = createSender(user);
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(user.getEmail());
            helper.setTo(targetEmail);
            helper.setSubject(subject);
            helper.setText(contentBuilder.toString(), true);
            List<String> filenames = original.getFilenames();
            if (filenames != null && !filenames.isEmpty()) {
                for (String filename : filenames) {
                    File file = new File(SAVE_PATH + filename);
                    if (file.exists()) helper.addAttachment(filename, file);
                }
            }
            sender.send(message);
            saveToSentFolder(user, message);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("转发失败: " + e.getMessage());
        }
    }

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
            if (!sentFolder.exists()) sentFolder = store.getFolder("Sent Messages");
            if (sentFolder.exists()) {
                sentFolder.open(Folder.READ_WRITE);
                message.setFlag(Flags.Flag.SEEN, true);
                sentFolder.appendMessages(new Message[] { message });
            }
        } catch (Exception e) {
        } finally {
            closeQuietly(sentFolder, store);
        }
    }

    private String getCorrectFolderName(String mailType, String uiFolderName) {
        if ("收件箱".equals(uiFolderName)) return "INBOX";
        if ("qq".equals(mailType)) {
            if ("已发送".equals(uiFolderName)) return "Sent Messages";
            if ("已删除".equals(uiFolderName) || "垃圾箱".equals(uiFolderName)) return "Deleted Messages";
        }
        if ("163".equals(mailType)) {
            if ("已发送".equals(uiFolderName)) return "已发送";
            if ("已删除".equals(uiFolderName) || "垃圾箱".equals(uiFolderName)) return "已删除";
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
        } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || (part.getFileName() != null && !part.getFileName().isEmpty())) {
            String fileName = MimeUtility.decodeText(part.getFileName());
            File saveDir = new File(SAVE_PATH);
            if (!saveDir.exists()) saveDir.mkdirs();
            try (InputStream is = part.getInputStream()) {
                Files.copy(is, new File(SAVE_PATH + fileName).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            attachments.add(fileName);
        }
    }

    private void closeQuietly(Folder folder, Store store) {
        try { if (folder != null && folder.isOpen()) folder.close(false); } catch (Exception e) {}
        try { if (store != null) store.close(); } catch (Exception e) {}
    }
}