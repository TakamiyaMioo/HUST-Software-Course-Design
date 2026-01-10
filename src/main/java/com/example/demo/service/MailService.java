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

    public static final String SAVE_PATH = "D:/email_data/";


    /**
     * 核心接收方法 (修复版：增加了异常保护和 163 兼容配置)
     */
    public Map<String, Object> receiveEmails(UserAccount user, String folderName, int page, int size,
                                             String sortField, String sortOrder, String keyword, String searchType) {

        // 1. 【修复关键点】先初始化默认值，防止发生异常时返回空 Map 导致 Controller 报 500 错误
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

            // 【核心修改】删除之前的 "如果不存在就回退到 INBOX" 的代码
            // if (!folder.exists()) { folder = store.getFolder("INBOX"); } <-- 删掉这行！

            if (!folder.exists()) {
                // 如果找不到，不再自作主张回退，而是抛出明确错误或返回空
                System.err.println("❌ 严重错误：在服务器上找不到文件夹 [" + realFolder + "]");
                // 尝试一些备用名称 (自动纠错逻辑)
                if (realFolder.equals("Sent Messages")) {
                    // 163 有时也叫 "Sent" 或 "已发送"
                    if (store.getFolder("Sent").exists())
                        folder = store.getFolder("Sent");
                    else if (store.getFolder("已发送").exists())
                        folder = store.getFolder("已发送");
                } else if (realFolder.equals("Deleted Messages")) {
                    if (store.getFolder("Trash").exists())
                        folder = store.getFolder("Trash");
                    else if (store.getFolder("已删除").exists())
                        folder = store.getFolder("已删除");
                }
            }

            // 二次检查，如果还是不存在，就只能报错了，不要显示收件箱混淆视听
            if (!folder.exists()) {
                System.err.println("❌ 最终确认文件夹不存在: " + realFolder);
                return result; // 直接返回空列表
            }

            folder.open(Folder.READ_ONLY);

            int totalMessages = folder.getMessageCount();
            result.put("totalCount", totalMessages); // 更新总数

            Message[] messages = null;

            // --- 策略判断 ---
            boolean hasKeyword = StringUtils.hasText(keyword);
            boolean isDefaultSort = (sortField == null || "date".equals(sortField))
                    && (sortOrder == null || "desc".equals(sortOrder));
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
            }

        } catch (Exception e) {
            // 打印错误日志，方便在控制台看到 163 到底报什么错
            System.err.println("❌ 邮件接收失败: " + e.getMessage());
            e.printStackTrace();
            // 注意：这里吞掉了异常，但因为我们在最开始初始化了 result，所以 Controller 不会崩，只会显示 "暂无邮件"
        } finally {
            closeQuietly(folder, store);
        }

        // --- 1. 内存搜索过滤 ---
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

    /**
     * 获取自定义文件夹列表 (剔除系统默认文件夹)
     */
    public List<String> getCustomFolders(UserAccount user) {
        List<String> customFolders = new ArrayList<>();
        Store store = null;
        Folder defaultFolder = null;
        try {
            store = getImapStore(user);

            // 获取根目录下的所有文件夹
            defaultFolder = store.getDefaultFolder();
            Folder[] allFolders = defaultFolder.list("*");

            // 定义需要剔除的系统文件夹名称 (包含英文和常见中文)
            // 注意：不同邮箱服务商的系统文件夹名字可能不一样
            List<String> systemFolders = Arrays.asList(
                    "INBOX", "收件箱",
                    "Sent Messages", "Sent", "已发送",
                    "Drafts", "Draft", "草稿箱",
                    "Deleted Messages", "Trash", "已删除", "垃圾箱", "Deleted",
                    "Junk", "Spam", "垃圾邮件", "广告邮件"
            );

            for (Folder f : allFolders) {
                // 获取文件夹名称
                String name = f.getName();

                // 简单的过滤逻辑：忽略系统文件夹
                // 注意：这里忽略大小写
                boolean isSystem = systemFolders.stream()
                        .anyMatch(sys -> sys.equalsIgnoreCase(name));

                // 还要排除也就是当前的 INBOX (IMAP 协议中 INBOX 是大小写敏感的，通常是大写)
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

    /**
     * 辅助方法：批量解析 Message 到 EmailInfo
     */
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

    // --- 保持其他方法不变 (getEmailDetail, moveToTrash, deleteMessage, sendMail,
    // forwardMail) ---
    // 为节省篇幅，这里假设你已经保留了上个版本中的这些方法代码

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
            if (msg == null)
                return null;
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
                if (fromName.isEmpty())
                    fromName = fromAddress;
            } else {
                fromAddress = fromFull;
            }
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
     * 移动邮件到垃圾箱 (增强版：修复 163 无法删除的问题)
     * 策略：尝试复制到垃圾箱 -> 如果成功，标记原邮件删除 -> 如果复制失败，也强制标记原邮件删除
     */
    public void moveToTrash(UserAccount user, String fromFolderName, long uid) {
        Store store = null;
        Folder sourceFolder = null;
        Folder trashFolder = null;
        try {
            store = getImapStore(user);

            // 1. 获取源文件夹
            String sourceRealName = getCorrectFolderName(user.getType(), fromFolderName);
            sourceFolder = store.getFolder(sourceRealName);
            if (!sourceFolder.exists()) {
                System.err.println("❌ 删除失败：源文件夹不存在 [" + sourceRealName + "]");
                return;
            }
            sourceFolder.open(Folder.READ_WRITE);

            // 2. 获取目标垃圾箱
            // 优先尝试映射的名字 "已删除"
            String trashName = getCorrectFolderName(user.getType(), "已删除");
            trashFolder = store.getFolder(trashName);

            // 【163 特殊保险】如果 "已删除" 不存在，尝试找 "Trash"
            if (!trashFolder.exists() && "163".equals(user.getType())) {
                if (store.getFolder("Trash").exists()) {
                    trashFolder = store.getFolder("Trash");
                }
            }

            // 3. 执行移动逻辑
            UIDFolder uidFolder = (UIDFolder) sourceFolder;
            Message msg = uidFolder.getMessageByUID(uid);

            if (msg != null) {
                boolean copySuccess = false;

                // 尝试复制到垃圾箱
                if (trashFolder != null && trashFolder.exists()) {
                    try {
                        trashFolder.open(Folder.READ_WRITE);
                        sourceFolder.copyMessages(new Message[] { msg }, trashFolder);
                        copySuccess = true;
                    } catch (Exception e) {
                        // 【关键修复】如果复制失败 (比如 163 经常报编码错误或禁止复制)，打印日志但不要停止
                        System.err.println("⚠️ 警告：无法移动到垃圾箱 (将执行强制删除): " + e.getMessage());
                        copySuccess = false;
                    }
                }

                // 【核心逻辑】无论复制是否成功，只要用户点了删除，就在原文件夹标记删除！
                // 这样能保证收件箱里的邮件一定会被删掉，解决了"删不掉"的问题。
                msg.setFlag(Flags.Flag.DELETED, true);
            }

            // 4. 物理清除 (提交删除操作)
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
        // 兼容旧调用：非回复场景
        sendMailWithAttachment(user, to, subject, content, file, null, null);
    }

    /**
     * 发送邮件（支持：1）用户选择的新附件；2）回复邮件时自动附带原邮件附件）
     *
     * 注意：浏览器出于安全原因无法在前端自动把附件“塞进” file input，
     * 所以回复场景下我们在后端根据 replyFolder + replyUid 重新取原邮件附件并附带发送。
     */
    /**
     * 发送邮件（支持群发，支持：1）用户选择的新附件；2）回复邮件时自动附带原邮件附件）
     */
    public void sendMailWithAttachment(UserAccount user, String to, String subject, String content,
                                       org.springframework.web.multipart.MultipartFile file,
                                       String replyFolder, Long replyUid) {
        try {
            JavaMailSenderImpl sender = createSender(user);
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(
                    message, true, "UTF-8");

            helper.setFrom(user.getEmail());

            // ==================== 【核心修改开始】 ====================
            // 处理多收件人：支持用 分号(;)、逗号(,)、中文逗号(，) 分隔
            if (to != null && !to.isEmpty()) {
                // 正则表达式：分割 [分号] [逗号] [中文逗号] 以及 [这些符号周围的空格]
                String[] recipients = to.split("[,;，\\s]+");
                helper.setTo(recipients);
            }
            // ==================== 【核心修改结束】 ====================

            helper.setSubject(subject);
            helper.setText(content, true);

            // 1) 用户手动选择的新附件
            if (file != null && !file.isEmpty()) {
                helper.addAttachment(file.getOriginalFilename(), file);
            }

            // 2) 回复时自动附带原邮件附件
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
            if (original == null)
                throw new RuntimeException("原邮件加载失败");
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
                    +
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
                    if (file.exists())
                        helper.addAttachment(filename, file);
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
            if (!sentFolder.exists())
                sentFolder = store.getFolder("Sent Messages");
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


    /**
     * 【最终版】获取正确的服务器文件夹名称
     * 根据抓取到的真实文件夹列表进行精确映射
     */
    private String getCorrectFolderName(String mailType, String uiFolderName) {
        // 1. 收件箱：所有邮箱统一叫 INBOX
        if ("收件箱".equals(uiFolderName)) {
            return "INBOX";
        }

        // 2. QQ 邮箱映射 (基于真实抓取结果)
        if ("qq".equals(mailType)) {
            if ("已发送".equals(uiFolderName))
                return "Sent Messages";
            if ("已删除".equals(uiFolderName))
                return "Deleted Messages";
            if ("草稿箱".equals(uiFolderName))
                return "Drafts";
            if ("垃圾箱".equals(uiFolderName))
                return "Junk";
        }

        // 3. 163 邮箱映射 (基于真实抓取结果：全是中文)
        if ("163".equals(mailType)) {
            if ("已发送".equals(uiFolderName))
                return "已发送";
            if ("已删除".equals(uiFolderName))
                return "已删除";
            if ("草稿箱".equals(uiFolderName))
                return "草稿箱"; // 对应你列表里的 "草稿箱"
            if ("垃圾箱".equals(uiFolderName))
                return "垃圾邮件"; // 对应你列表里的 "垃圾邮件"
        }

        // 4. 其他情况返回原名
        return uiFolderName;
    }

    private JavaMailSenderImpl createSender(UserAccount user) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(user.getSmtpHost());
        sender.setPort(user.getSmtpPort()); // 465
        sender.setUsername(user.getEmail());
        sender.setPassword(user.getPassword());
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.trust", "*");

        // 【修复】163 和 QQ 使用 465 端口时，都必须开启 SSL
        if ("qq".equals(user.getType()) || "163".equals(user.getType())) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        return sender;
    }
    /**
     * 递归解析邮件内容（修复版）
     * 1. 智能处理 multipart/alternative，优先取 HTML，避免重复
     * 2. 纯文本转 HTML，解决换行丢失问题
     */
    private void parseMessage(Part part, StringBuilder bodyText, List<String> attachments) throws Exception {
        // 1. 处理纯文本：将换行符转换为 <br>
        if (part.isMimeType("text/plain")) {
            String txt = part.getContent().toString();
            // 防止 null
            if (txt == null)
                txt = "";
            // 将换行符转为 HTML 换行，并包裹 div 保持样式
            bodyText.append("<div style='font-family: sans-serif; white-space: pre-wrap;'>")
                    .append(txt) // pre-wrap 能保留原有的换行和空格，也可以用 .replace("\n", "<br>")
                    .append("</div>");
        }
        // 2. 处理 HTML：直接拼接
        else if (part.isMimeType("text/html")) {
            bodyText.append(part.getContent().toString());
        }
        // 3. 处理多部分内容 (Multipart)
        else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();

            // --- 核心修复：针对 multipart/alternative (多重选择) 的特殊处理 ---
            if (part.isMimeType("multipart/alternative")) {
                Part bestPart = null;
                // 策略：优先找 HTML
                for (int i = 0; i < multipart.getCount(); i++) {
                    Part p = multipart.getBodyPart(i);
                    if (p.isMimeType("text/html")) {
                        bestPart = p;
                        break;
                    }
                }
                // 如果没找到 HTML，再找纯文本
                if (bestPart == null) {
                    for (int i = 0; i < multipart.getCount(); i++) {
                        Part p = multipart.getBodyPart(i);
                        if (p.isMimeType("text/plain")) {
                            bestPart = p;
                            break;
                        }
                    }
                }

                // 如果找到了最佳部分，只解析这一个！
                if (bestPart != null) {
                    parseMessage(bestPart, bodyText, attachments);
                } else {
                    // 极端情况：都没有，那就按默认逻辑全部遍历（防止漏掉内容）
                    for (int i = 0; i < multipart.getCount(); i++) {
                        parseMessage(multipart.getBodyPart(i), bodyText, attachments);
                    }
                }
            } else {
                // --- 对于 multipart/mixed 或 related (包含附件或内嵌图)，遍历所有部分 ---
                for (int i = 0; i < multipart.getCount(); i++) {
                    parseMessage(multipart.getBodyPart(i), bodyText, attachments);
                }
            }
        }
        // 4. 处理附件
        // --- 找到这段代码 ---
                else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) ||
                        (part.getFileName() != null && !part.getFileName().isEmpty())) {
                    String fileName = MimeUtility.decodeText(part.getFileName());
                    
                    // 【核心修复点】：提取纯文件名，防止包含路径
                    if (fileName.contains("/") || fileName.contains("\\")) {
                        // 取最后一个斜杠或反斜杠之后的内容
                        int lastIndex = Math.max(fileName.lastIndexOf("/"), fileName.lastIndexOf("\\"));
                        fileName = fileName.substring(lastIndex + 1);
                    }

                    File saveDir = new File(SAVE_PATH);
                    if (!saveDir.exists()) saveDir.mkdirs();
                    
                    try (InputStream is = part.getInputStream()) {
                        // 现在 fileName 只是 "xxx.jpg"，拼接后路径合法
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

    /**
     * 1. 创建自定义文件夹
     */
    public void createFolder(UserAccount user, String folderName) throws Exception {
        Store store = null;
        try {
            // 获取连接 (复用原有的连接逻辑，这里简化写出，建议抽取 getStore 方法)
            store = getStore(user); // 这里的 getStore 是为了节省篇幅，实际可以用你原有的连接代码

            Folder defaultFolder = store.getDefaultFolder();
            Folder newFolder = defaultFolder.getFolder(folderName);

            if (newFolder.exists()) {
                throw new RuntimeException("文件夹已存在");
            }

            // HOLDS_MESSAGES 表示该文件夹用于存放邮件
            boolean success = newFolder.create(Folder.HOLDS_MESSAGES);
            if (!success) {
                throw new RuntimeException("创建文件夹失败");
            }
        } finally {
            closeQuietly(null, store);
        }
    }

    /**
     * 2. 删除文件夹 (包含安全校验)
     */
    public void deleteFolder(UserAccount user, String folderName) throws Exception {
        // 安全检查：禁止删除系统文件夹
        // 注意：不同邮箱服务商的系统文件夹可能不同，这里列出常见的
        List<String> systemFolders = Arrays.asList("INBOX", "收件箱", "Sent", "已发送", "Drafts", "草稿箱", "Trash", "已删除", "Junk", "垃圾箱");
        if (systemFolders.stream().anyMatch(s -> s.equalsIgnoreCase(folderName))) {
            throw new RuntimeException("系统文件夹不能删除");
        }

        Store store = null;
        try {
            store = getStore(user);
            Folder folder = store.getFolder(folderName);

            if (folder.exists()) {
                // true 表示递归删除 (如果里面有邮件也一并删除)
                folder.delete(true);
            } else {
                throw new RuntimeException("文件夹不存在");
            }
        } finally {
            closeQuietly(null, store);
        }
    }

    /**
     * 3. 移动邮件 (复制 + 删除)
     */
    public void moveMessage(UserAccount user, String fromFolder, String toFolder, long uid) throws Exception {
        Store store = null;
        Folder source = null;
        Folder target = null;
        try {
            store = getStore(user);

            // 打开源文件夹
            String realSource = getCorrectFolderName(user.getType(), fromFolder);
            source = store.getFolder(realSource);
            source.open(Folder.READ_WRITE);

            // 打开目标文件夹
            // 注意：移动操作通常不涉及系统名称映射转换，直接用 folderName 即可，除非目标也是系统文件夹
            String realTarget = getCorrectFolderName(user.getType(), toFolder);
            // 如果目标是自定义文件夹，getCorrectFolderName 会直接返回原名，所以这样写兼容性好

            target = store.getFolder(realTarget);
            if (!target.exists()) {
                throw new RuntimeException("目标文件夹不存在");
            }
            target.open(Folder.READ_WRITE);

            UIDFolder uidSource = (UIDFolder) source;
            Message msg = uidSource.getMessageByUID(uid);

            if (msg != null) {
                // 1. 复制到新文件夹
                source.copyMessages(new Message[]{msg}, target);
                // 2. 在旧文件夹标记删除
                msg.setFlag(Flags.Flag.DELETED, true);
                // 3. 物理清除 (部分邮箱服务器需要这一步才能真正移走)
                source.expunge();
            }
        } finally {
            closeQuietly(target, null); // 先关 target
            closeQuietly(source, store); // 再关 source 和 store
        }
    }

    // --- 辅助方法：为了减少重复代码，建议抽取一个获取 Store 的方法 ---
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
    // ================== 核心通用方法 (复用逻辑) ==================

    /**
     * 【通用 IMAP 连接器】
     * 统一处理了 SSL、端口、Trust 设置，最重要的是统一处理了 163 的 IMAP ID 验证
     */
    private Store getImapStore(UserAccount user) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.host", user.getImapHost());
        props.put("mail.imap.port", "993");
        props.put("mail.imap.ssl.enable", "true");
        props.put("mail.imap.partialfetch", "false"); // 关闭部分抓取，防乱码
        props.put("mail.imap.ssl.trust", "*"); // 信任所有证书，防握手失败

        Session session = Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect(user.getImapHost(), user.getEmail(), user.getPassword());

        // --- 核心：统一发送 IMAP ID (解决 163 报错问题) ---
        if (store instanceof IMAPStore) {
            IMAPStore imapStore = (IMAPStore) store;
            Map<String, String> idMap = new HashMap<>();
            idMap.put("name", "my-email-client");
            idMap.put("version", "1.0.0");
            idMap.put("vendor", "my-company");
            idMap.put("support-email", "test@test.com");
            try {
                imapStore.id(idMap);
            } catch (Exception e) {
                // 忽略非关键错误
            }
        }
        return store;
    }
    // 在 MailService.java 中添加这个方法

    /**
     * 【调试专用】获取服务器上所有的文件夹名称列表
     */
    public List<String> getAllFolders(UserAccount user) {
        List<String> folderNames = new ArrayList<>();
        Store store = null;
        try {
            store = getImapStore(user); // 复用之前的连接方法
            Folder defaultFolder = store.getDefaultFolder();

            // list("*") 表示列出所有层级的文件夹
            for (Folder f : defaultFolder.list("*")) {
                // 将 "名称 (全名)" 的格式存入列表
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
}