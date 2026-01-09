
package com.example.demo.service;

import com.example.demo.model.EmailInfo;
import com.example.demo.model.UserAccount;
import jakarta.mail.*;
import jakarta.mail.internet.MimeUtility;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.eclipse.angus.mail.imap.IMAPStore;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MailService {

    // 附件存储路径
    public static final String SAVE_PATH = "D:/email_data/";

    /**
     * 核心接收方法
     */
    public Map<String, Object> receiveEmails(UserAccount user, String folderName, int page, int size,
                                             String sortField, String sortOrder, String keyword, String searchType) {

        Map<String, Object> result = new HashMap<>();
        result.put("totalCount", 0);
        result.put("list", new ArrayList<EmailInfo>());

        List<EmailInfo> fullList = new ArrayList<>();
        Store store = null;
        Folder folder = null;
        try {
            store = getImapStore(user);

            String realFolder = getCorrectFolderName(user.getType(), folderName);
            folder = store.getFolder(realFolder);

            if (!folder.exists()) {
                System.err.println("❌ 严重错误：在服务器上找不到文件夹 [" + realFolder + "]");
                if (realFolder.equals("Sent Messages")) {
                    if (store.getFolder("Sent").exists()) folder = store.getFolder("Sent");
                    else if (store.getFolder("已发送").exists()) folder = store.getFolder("已发送");
                } else if (realFolder.equals("Deleted Messages")) {
                    if (store.getFolder("Trash").exists()) folder = store.getFolder("Trash");
                    else if (store.getFolder("已删除").exists()) folder = store.getFolder("已删除");
                }
            }

            if (!folder.exists()) {
                System.err.println("❌ 最终确认文件夹不存在: " + realFolder);
                return result;
            }

            folder.open(Folder.READ_ONLY);

            int totalMessages = folder.getMessageCount();
            result.put("totalCount", totalMessages);

            Message[] messages = null;

            boolean hasKeyword = StringUtils.hasText(keyword);
            boolean isDefaultSort = (sortField == null || "date".equals(sortField))
                    && (sortOrder == null || "desc".equals(sortOrder));
            boolean useServerSidePaging = !hasKeyword && isDefaultSort;

            if (useServerSidePaging) {
                int end = totalMessages - (page - 1) * size;
                int start = end - size + 1;
                if (end > 0) {
                    if (start < 1) start = 1;
                    messages = folder.getMessages(start, end);
                }
            } else {
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
            }

        } catch (Exception e) {
            System.err.println("❌ 邮件接收失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            closeQuietly(folder, store);
        }

        // 内存搜索过滤
        if (StringUtils.hasText(keyword)) {
            String k = keyword.toLowerCase();
            String type = (searchType != null) ? searchType : "all";

            fullList = fullList.stream()
                    .filter(email -> {
                        boolean match = false;
                        switch (type) {
                            case "sender":
                                match = (email.getSender() != null && email.getSender().toLowerCase().contains(k));
                                break;
                            case "title":
                                match = (email.getTitle() != null && email.getTitle().toLowerCase().contains(k));
                                break;
                            case "date":
                                match = (email.getSendDate() != null && email.getSendDate().contains(k));
                                break;
                            case "all":
                            default:
                                match = (email.getTitle() != null && email.getTitle().toLowerCase().contains(k)) ||
                                        (email.getSender() != null && email.getSender().toLowerCase().contains(k)) ||
                                        (email.getSendDate() != null && email.getSendDate().contains(k));
                                break;
                        }
                        return match;
                    })
                    .collect(Collectors.toList());
            result.put("totalCount", fullList.size());
        }

        // 内存排序
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

        // 内存分页
        boolean hasKeyword2 = StringUtils.hasText(keyword);
        boolean isDefaultSort2 = (sortField == null || "date".equals(sortField))
                && (sortOrder == null || "desc".equals(sortOrder));
        boolean useServerSidePaging2 = !hasKeyword2 && isDefaultSort2;

        List<EmailInfo> pageList;
        if (useServerSidePaging2) {
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

    public List<String> getCustomFolders(UserAccount user) {
        List<String> customFolders = new ArrayList<>();
        Store store = null;
        Folder defaultFolder = null;
        try {
            store = getImapStore(user);
            defaultFolder = store.getDefaultFolder();
            Folder[] allFolders = defaultFolder.list("*");
            List<String> systemFolders = Arrays.asList(
                    "INBOX", "收件箱",
                    "Sent Messages", "Sent", "已发送",
                    "Drafts", "Draft", "草稿箱",
                    "Deleted Messages", "Trash", "已删除", "垃圾箱", "Deleted",
                    "Junk", "Spam", "垃圾邮件", "广告邮件"
            );

            for (Folder f : allFolders) {
                String name = f.getName();
                boolean isSystem = systemFolders.stream().anyMatch(sys -> sys.equalsIgnoreCase(name));
                if (!isSystem && !"INBOX".equalsIgnoreCase(name)) {
                    customFolders.add(name);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (store != null) store.close();
            } catch (Exception e) {}
        }
        return customFolders;
    }

    private List<EmailInfo> parseMessages(UIDFolder uidFolder, Message[] messages, boolean isSentFolder)
            throws Exception {
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

    public EmailInfo getEmailDetail(UserAccount user, String folderName, long uid) {
        Store store = null;
        Folder folder = null;
        try {
            store = getImapStore(user);
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
            store = getImapStore(user);
            String sourceRealName = getCorrectFolderName(user.getType(), fromFolderName);
            sourceFolder = store.getFolder(sourceRealName);
            if (!sourceFolder.exists()) {
                System.err.println("❌ 删除失败：源文件夹不存在 [" + sourceRealName + "]");
                return;
            }
            sourceFolder.open(Folder.READ_WRITE);

            String trashName = getCorrectFolderName(user.getType(), "已删除");
            trashFolder = store.getFolder(trashName);
            if (!trashFolder.exists() && "163".equals(user.getType())) {
                if (store.getFolder("Trash").exists()) {
                    trashFolder = store.getFolder("Trash");
                }
            }

            UIDFolder uidFolder = (UIDFolder) sourceFolder;
            Message msg = uidFolder.getMessageByUID(uid);

            if (msg != null) {
                if (trashFolder != null && trashFolder.exists()) {
                    try {
                        trashFolder.open(Folder.READ_WRITE);
                        sourceFolder.copyMessages(new Message[] { msg }, trashFolder);
                    } catch (Exception e) {
                        System.err.println("⚠️ 警告：无法移动到垃圾箱 (将执行强制删除): " + e.getMessage());
                    }
                }
                msg.setFlag(Flags.Flag.DELETED, true);
            }
            sourceFolder.expunge();
        } catch (Exception e) {
            System.err.println("❌ 删除流程严重错误: " + e.getMessage());
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
            store = getImapStore(user);
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

    public void sendMailWithAttachment(UserAccount user, String to, String subject, String content,
                                       org.springframework.web.multipart.MultipartFile file) {
        sendMailWithAttachment(user, to, subject, content, file, null, null);
    }

    public void sendMailWithAttachment(UserAccount user, String to, String subject, String content,
                                       org.springframework.web.multipart.MultipartFile file,
                                       String replyFolder, Long replyUid) {
        try {
            JavaMailSenderImpl sender = createSender(user);
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(
                    message, true, "UTF-8");

            helper.setFrom(user.getEmail());
            if (to != null && !to.isEmpty()) {
                String[] recipients = to.split("[,;，\\s]+");
                helper.setTo(recipients);
            }
            helper.setSubject(subject);
            helper.setText(content, true);

            if (file != null && !file.isEmpty()) {
                helper.addAttachment(file.getOriginalFilename(), file);
            }

            if (replyUid != null && replyFolder != null && !replyFolder.isEmpty()) {
                EmailInfo original = getEmailDetail(user, replyFolder, replyUid);
                if (original != null) {
                    List<String> filenames = original.getFilenames();
                    if (filenames != null && !filenames.isEmpty()) {
                        for (String filename : filenames) {
                            File f = new File(SAVE_PATH + filename);
                            if (f.exists()) {
                                helper.addAttachment(filename, f);
                            }
                        }
                    }
                }
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
                contentBuilder.append("<div style='margin-bottom: 20px; font-size: 14px;'>")
                        .append(userComment.replace("\n", "<br>")).append("</div>");
            }
            String senderStr = original.getSender();
            if (original.getAddress() != null && !original.getAddress().isEmpty())
                senderStr += " &lt;" + original.getAddress() + "&gt;";
            String recipientsStr = original.getRecipients();
            if (recipientsStr != null)
                recipientsStr = recipientsStr.replace("<", "&lt;").replace(">", "&gt;");
            String quoteHeader = "<div style='background:#f2f2f2; padding:10px; font-size:12px; color:#333; line-height:1.6; border-radius:5px;'>"
                    + "<div>------------------ 原始邮件 ------------------</div>" +
                    "<div><b>发件人:</b> " + senderStr + "</div>" +
                    "<div><b>发送时间:</b> " + original.getSendDate() + "</div>" +
                    "<div><b>收件人:</b> " + (recipientsStr != null ? recipientsStr : "") + "</div>" +
                    "<div><b>主题:</b> " + original.getTitle() + "</div>" +
                    "</div><br>";
            contentBuilder.append(quoteHeader);
            contentBuilder.append(original.getContent());
            JavaMailSenderImpl sender = createSender(user);
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(
                    message, true, "UTF-8");
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
            store = getImapStore(user);
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
            if ("已删除".equals(uiFolderName)) return "Deleted Messages";
            if ("草稿箱".equals(uiFolderName)) return "Drafts";
            if ("垃圾箱".equals(uiFolderName)) return "Junk";
        }
        if ("163".equals(mailType)) {
            if ("已发送".equals(uiFolderName)) return "已发送";
            if ("已删除".equals(uiFolderName)) return "已删除";
            if ("草稿箱".equals(uiFolderName)) return "草稿箱";
            if ("垃圾箱".equals(uiFolderName)) return "垃圾邮件";
        }
        if ("hust".equals(mailType)) {
            if ("已发送".equals(uiFolderName)) return "Sent Items";
            if ("已删除".equals(uiFolderName)) return "Trash";
            if ("草稿箱".equals(uiFolderName)) return "Drafts";
            if ("垃圾箱".equals(uiFolderName)) return "Junk E-mail";
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
        props.put("mail.smtp.ssl.trust", "*");
        if ("qq".equals(user.getType()) || "163".equals(user.getType()) || "hust".equals(user.getType())) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        return sender;
    }

    private void closeQuietly(Folder folder, Store store) {
        try { if (folder != null && folder.isOpen()) folder.close(false); } catch (Exception e) {}
        try { if (store != null) store.close(); } catch (Exception e) {}
    }

    public void createFolder(UserAccount user, String folderName) throws Exception {
        Store store = null;
        try {
            store = getStore(user);
            Folder defaultFolder = store.getDefaultFolder();
            Folder newFolder = defaultFolder.getFolder(folderName);
            if (newFolder.exists()) throw new RuntimeException("文件夹已存在");
            boolean success = newFolder.create(Folder.HOLDS_MESSAGES);
            if (!success) throw new RuntimeException("创建文件夹失败");
        } finally {
            closeQuietly(null, store);
        }
    }

    public void deleteFolder(UserAccount user, String folderName) throws Exception {
        List<String> systemFolders = Arrays.asList("INBOX", "收件箱", "Sent", "已发送", "Drafts", "草稿箱", "Trash", "已删除", "Junk", "垃圾箱");
        if (systemFolders.stream().anyMatch(s -> s.equalsIgnoreCase(folderName))) {
            throw new RuntimeException("系统文件夹不能删除");
        }
        Store store = null;
        try {
            store = getStore(user);
            Folder folder = store.getFolder(folderName);
            if (folder.exists()) folder.delete(true);
            else throw new RuntimeException("文件夹不存在");
        } finally {
            closeQuietly(null, store);
        }
    }

    public void moveMessage(UserAccount user, String fromFolder, String toFolder, long uid) throws Exception {
        Store store = null;
        Folder source = null;
        Folder target = null;
        try {
            store = getStore(user);
            String realSource = getCorrectFolderName(user.getType(), fromFolder);
            source = store.getFolder(realSource);
            source.open(Folder.READ_WRITE);

            String realTarget = getCorrectFolderName(user.getType(), toFolder);
            target = store.getFolder(realTarget);
            if (!target.exists()) throw new RuntimeException("目标文件夹不存在");
            target.open(Folder.READ_WRITE);

            UIDFolder uidSource = (UIDFolder) source;
            Message msg = uidSource.getMessageByUID(uid);

            if (msg != null) {
                source.copyMessages(new Message[]{msg}, target);
                msg.setFlag(Flags.Flag.DELETED, true);
                source.expunge();
            }
        } finally {
            closeQuietly(target, null);
            closeQuietly(source, store);
        }
    }

    private Store getStore(UserAccount user) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.host", user.getImapHost());
        props.put("mail.imap.port", "993");
        props.put("mail.imap.ssl.enable", "true");
        props.put("mail.imap.ssl.trust", "*");
        jakarta.mail.Session session = jakarta.mail.Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect(user.getImapHost(), user.getEmail(), user.getPassword());
        return store;
    }

    private Store getImapStore(UserAccount user) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.host", user.getImapHost());
        props.put("mail.imap.port", "993");
        props.put("mail.imap.ssl.enable", "true");
        props.put("mail.imap.partialfetch", "false");
        props.put("mail.imap.ssl.trust", "*");

        Session session = Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect(user.getImapHost(), user.getEmail(), user.getPassword());

        if (store instanceof IMAPStore) {
            IMAPStore imapStore = (IMAPStore) store;
            Map<String, String> idMap = new HashMap<>();
            idMap.put("name", "my-email-client");
            idMap.put("version", "1.0.0");
            idMap.put("vendor", "my-company");
            idMap.put("support-email", "test@test.com");
            try {
                imapStore.id(idMap);
            } catch (Exception e) {}
        }
        return store;
    }

    public List<String> getAllFolders(UserAccount user) {
        List<String> folderNames = new ArrayList<>();
        Store store = null;
        try {
            store = getImapStore(user);
            Folder defaultFolder = store.getDefaultFolder();
            for (Folder f : defaultFolder.list("*")) {
                folderNames.add(f.getName());
            }
        } catch (Exception e) {
            e.printStackTrace();
            folderNames.add("获取失败: " + e.getMessage());
        } finally {
            closeQuietly(null, store);
        }
        return folderNames;
    }

    /**
     * 【修复版】递归解析邮件内容
     * 1. 智能处理 multipart/alternative
     * 2. 纯文本转 HTML
     * 3. 清洗附件文件名 (去除非法路径)
     */
    private void parseMessage(Part part, StringBuilder bodyText, List<String> attachments) throws Exception {
        if (part.isMimeType("text/plain")) {
            String txt = part.getContent().toString();
            if (txt == null) txt = "";
            bodyText.append("<div style='font-family: sans-serif; white-space: pre-wrap;'>")
                    .append(txt).append("</div>");
        } else if (part.isMimeType("text/html")) {
            bodyText.append(part.getContent().toString());
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            if (part.isMimeType("multipart/alternative")) {
                Part bestPart = null;
                for (int i = 0; i < multipart.getCount(); i++) {
                    Part p = multipart.getBodyPart(i);
                    if (p.isMimeType("text/html")) {
                        bestPart = p;
                        break;
                    }
                }
                if (bestPart == null) {
                    for (int i = 0; i < multipart.getCount(); i++) {
                        Part p = multipart.getBodyPart(i);
                        if (p.isMimeType("text/plain")) {
                            bestPart = p;
                            break;
                        }
                    }
                }
                if (bestPart != null) {
                    parseMessage(bestPart, bodyText, attachments);
                } else {
                    for (int i = 0; i < multipart.getCount(); i++) {
                        parseMessage(multipart.getBodyPart(i), bodyText, attachments);
                    }
                }
            } else {
                for (int i = 0; i < multipart.getCount(); i++) {
                    parseMessage(multipart.getBodyPart(i), bodyText, attachments);
                }
            }
        } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) ||
                (part.getFileName() != null && !part.getFileName().isEmpty())) {

            String fileName = MimeUtility.decodeText(part.getFileName());

            // --- 核心修复：清洗文件名 ---
            if (fileName != null) {
                int lastWin = fileName.lastIndexOf('\\');
                int lastUnix = fileName.lastIndexOf('/');
                int index = Math.max(lastWin, lastUnix);
                if (index != -1) {
                    fileName = fileName.substring(index + 1);
                }
            }
            // ------------------------

            File saveDir = new File(SAVE_PATH);
            if (!saveDir.exists()) saveDir.mkdirs();
            try (InputStream is = part.getInputStream()) {
                Files.copy(is, new File(SAVE_PATH + fileName).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            attachments.add(fileName);
        }
    }
}