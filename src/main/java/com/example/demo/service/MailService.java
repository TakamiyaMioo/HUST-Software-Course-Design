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

/**
 * 邮件服务核心类 (Service Layer)
 * 作用：封装底层复杂的 JavaMail API，向 Controller 提供简单易用的方法。
 * * 💡 核心知识点：
 * 1. IMAP vs POP3：这里主要用 IMAP，因为它支持双向同步（你读了邮件，服务器状态也会变），且支持文件夹管理。
 * 2. Store vs Folder vs Message：这是 JavaMail 的三个核心对象，分别代表“连接会话”、“文件夹”、“单封邮件”。
 */
@Service
public class MailService {

    // 附件保存路径
    // ⚠️ 注意：Windows使用 D:/...，Linux服务器通常使用 /var/data/...
    // 如果路径不存在，代码里会有逻辑自动创建文件夹
    public static final String SAVE_PATH = "D:/email_data/";



    /**
     * 【核心方法】接收邮件列表
     * 这个方法最复杂，因为它要处理分页、排序、搜索以及不同邮箱厂商(QQ/163)的兼容性。
     *
     * @param user       当前登录的用户信息（包含邮箱地址、解密后的授权码、服务器地址）
     * @param folderName 前端传来的文件夹名（如 "收件箱"、"已发送"）
     * @param page       当前页码（从1开始）
     * @param size       每页显示多少条
     * @param sortField  排序字段（date, sender, title）
     * @param sortOrder  排序顺序（asc, desc）
     * @param keyword    搜索关键词
     * @param searchType 搜索范围
     * @return Map       包含两个 key: "list" (邮件列表), "totalCount" (总数)
     */
    public Map<String, Object> receiveEmails(UserAccount user, String folderName, int page, int size,
                                             String sortField, String sortOrder, String keyword, String searchType) {

        // 1. 【防御性编程】预先初始化返回结果
        // 为什么要先初始化？因为如果后面 try 块里报错了，我们不想让整个网页崩掉（HTTP 500），
        // 而是返回一个空列表，这样用户至少能看到页面框架，只是没有数据。
        Map<String, Object> result = new HashMap<>();
        result.put("totalCount", 0);
        result.put("list", new ArrayList<EmailInfo>());

        List<EmailInfo> fullList = new ArrayList<>();
        Store store = null;  // 代表与邮件服务器的连接对象
        Folder folder = null; // 代表具体的文件夹对象

        try {
            // 2. 建立连接 (调用下面的 getImapStore 方法)
            // 这里会进行 SSL 握手、密码验证，也是最容易报错（连接超时、密码错误）的地方
            // 内部包含了针对 163 邮箱 "Unsafe Login" 错误的特殊处理 ID 命令
            store = getImapStore(user);

            // 3. 文件夹名称映射
            // 前端显示的是“已发送”，但服务器上可能叫 "Sent Messages" (QQ) 或 "Sent" (163)。
            // getCorrectFolderName 方法负责做这个翻译工作。
            String realFolder = getCorrectFolderName(user.getType(), folderName);
            folder = store.getFolder(realFolder);

            // 4. 【关键修改】文件夹存在性检查与自动纠错
            // 现在的逻辑是：如果文件夹不存在，尝试智能纠错，纠错失败则报错，不再强行跳转到收件箱混淆视听。
            if (!folder.exists()) {
                System.err.println("❌ 严重错误：在服务器上找不到文件夹 [" + realFolder + "]");

                // --- 自动纠错逻辑 ---
                // 不同服务器对 "已发送" 和 "垃圾箱" 的命名五花八门，尝试备选方案
                if (realFolder.equals("Sent Messages")) {
                    // 163/Coremail 有时也叫 "Sent" 或中文 "已发送"
                    if (store.getFolder("Sent").exists())
                        folder = store.getFolder("Sent");
                    else if (store.getFolder("已发送").exists())
                        folder = store.getFolder("已发送");
                } else if (realFolder.equals("Deleted Messages")) {
                    // 垃圾箱同理，有的叫 Trash，有的叫 Deleted
                    if (store.getFolder("Trash").exists())
                        folder = store.getFolder("Trash");
                    else if (store.getFolder("已删除").exists())
                        folder = store.getFolder("已删除");
                }
            }

            // 二次检查，如果还是不存在，说明真的没有这个文件夹，直接返回空结果
            if (!folder.exists()) {
                System.err.println("❌ 最终确认文件夹不存在: " + realFolder);
                return result;
            }

            // 5. 打开文件夹
            // Folder.READ_ONLY：只读模式。这很重要！
            // 原因 1: 速度快，不需要锁定文件夹。
            // 原因 2: 防止我们在读取列表时不小心把未读邮件标记为已读了 (SEEN flag)。
            folder.open(Folder.READ_ONLY);

            int totalMessages = folder.getMessageCount();
            result.put("totalCount", totalMessages); // 更新总邮件数

            Message[] messages = null;

            // --- 6. 分页策略 (性能优化的关键点！) ---

            // 场景 A [极速模式]：没有搜索关键字，且按时间倒序（默认情况）。
            // 此时我们可以直接利用 IMAP 协议的特性，只下载第 1-10 封邮件的头信息。
            // 不需要下载几千封邮件，速度极快。
            boolean hasKeyword = StringUtils.hasText(keyword);
            boolean isDefaultSort = (sortField == null || "date".equals(sortField))
                    && (sortOrder == null || "desc".equals(sortOrder));
            boolean useServerSidePaging = !hasKeyword && isDefaultSort;

            if (useServerSidePaging) {
                // [极速模式] 服务器端分页
                // 注意：JavaMail 的索引是从 1 开始的，且最大的索引是最新的邮件。
                // 比如总共 100 封，第 1 页取 91-100。
                int end = totalMessages - (page - 1) * size;
                int start = end - size + 1;
                if (end > 0) {
                    if (start < 1) start = 1; // 防止索引越界
                    messages = folder.getMessages(start, end); // 只抓取这 10 封
                }
            } else {
                // 场景 B [全量模式]：用户要搜索或按发件人排序。
                // IMAP 对中文搜索支持很差，所以我们只能把所有邮件的“信封信息”都拉下来，
                // 然后在 Java 内存里过滤。这会慢一些，但是功能最全。
                if (totalMessages > 0) {
                    messages = folder.getMessages(); // 抓取所有邮件对象（此时还没下载内容）
                }
            }

            if (messages != null && messages.length > 0) {
                // 7. 性能优化神器：FetchProfile
                // 默认情况下，当你调用 message.getSubject() 时，JavaMail 才会发网络请求去取标题。
                // 如果有 100 封邮件，就会发 100 次网络请求（N+1问题），巨慢无比。
                // FetchProfile 告诉服务器：“请一次性把这 100 封邮件的标题、发件人、时间打包发给我”。
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE); // 包含主题、发件人、时间
                fp.add(UIDFolder.FetchProfileItem.UID); // 包含唯一ID
                folder.fetch(messages, fp); // 批量预加载

                // 解析邮件，转换为我们自己的 EmailInfo 对象
                boolean isSentFolder = realFolder.equalsIgnoreCase("Sent Messages") || realFolder.equals("已发送");
                fullList = parseMessages((UIDFolder) folder, messages, isSentFolder);
            }

        } catch (Exception e) {
            // 打印错误日志，方便调试 (特别是 163 报错)
            System.err.println("❌ 邮件接收失败: " + e.getMessage());
            e.printStackTrace();
            // 这里不抛出异常，而是让方法正常结束返回空 result，保证前端页面能加载出框架
        } finally {
            // 8. 资源释放 (非常重要！)
            // 如果不关闭 Folder 和 Store，连接会一直占用，很快就会达到邮箱服务器的连接数上限（通常是 10-20 个），
            // 导致后续无法登录。
            closeQuietly(folder, store);
        }

        // --- 9. 内存搜索过滤 (Java Stream API) ---
        // 对应之前的 [全量模式]，数据都在内存里了，现在进行关键词匹配
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

            // 更新搜索后的结果总数
            result.put("totalCount", fullList.size());
        }

        // --- 10. 内存排序 ---
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

        // 处理倒序
        if ("desc".equals(sortOrder)) {
            if (comparator != null) comparator = comparator.reversed();
        }

        if (!fullList.isEmpty() && comparator != null) {
            Collections.sort(fullList, comparator);
        }

        // --- 11. 内存分页 ---
        // 如果前面走了全量模式（为了搜索/排序），这里需要手动切片取出当前页的数据
        boolean hasKeyword2 = StringUtils.hasText(keyword);
        boolean isDefaultSort2 = (sortField == null || "date".equals(sortField))
                && (sortOrder == null || "desc".equals(sortOrder));
        boolean useServerSidePaging2 = !hasKeyword2 && isDefaultSort2;

        List<EmailInfo> pageList;
        if (useServerSidePaging2) {
            // 如果已经是服务器端分页，fullList 就是当前页数据
            pageList = fullList;
        } else {
            // 手动 subList 切片
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
     * 获取自定义文件夹列表
     * 逻辑：获取服务器所有文件夹 -> 剔除系统默认文件夹 (如 INBOX, Trash 等) -> 返回剩余的
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

            // 定义黑名单：需要剔除的系统文件夹名称 (兼容中英文)
            // 这些文件夹由系统管理，不应该显示在“自定义文件夹”区域
            List<String> systemFolders = Arrays.asList(
                    "INBOX", "收件箱",
                    "Sent Messages", "Sent", "已发送",
                    "Drafts", "Draft", "草稿箱",
                    "Deleted Messages", "Trash", "已删除", "垃圾箱", "Deleted",
                    "Junk", "Spam", "垃圾邮件", "广告邮件"
            );

            for (Folder f : allFolders) {
                String name = f.getName();

                // 忽略系统文件夹
                boolean isSystem = systemFolders.stream()
                        .anyMatch(sys -> sys.equalsIgnoreCase(name));

                // 还要单独排除 "INBOX" (IMAP 标准名称)
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
     * 辅助方法：批量将 JavaMail 的 Message 对象解析为轻量级的 EmailInfo 对象
     * 仅解析信封信息 (标题、发件人、时间)，不解析正文，速度快。
     * 用于列表页展示。
     */
    private List<EmailInfo> parseMessages(UIDFolder uidFolder, Message[] messages, boolean isSentFolder)
            throws Exception {
        List<EmailInfo> list = new ArrayList<>();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        for (Message msg : messages) {
            try {
                // 获取 UID (唯一标识)
                long uid = uidFolder.getUID(msg);
                // 解码标题 (处理 =?UTF-8?B?... 格式的 MIME 编码)
                String subject = (msg.getSubject() != null) ? MimeUtility.decodeText(msg.getSubject()) : "无标题";

                String fullString = "";
                // 逻辑判断：如果是“已发送”箱，我们要看的是“收件人”是谁；否则看“发件人”
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

                // 字符串处理：分离 姓名 和 邮箱地址
                // 格式通常是: "张三 <zhangsan@qq.com>"
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

                // 添加到列表
                list.add(new EmailInfo(uid, subject, displayName, address, sentDate, null, new ArrayList<>()));
            } catch (Exception e) {
                // 单个邮件解析失败不影响整体
            }
        }
        return list;
    }

    /**
     * 获取单封邮件详情
     * 与 receiveEmails 不同，这个方法会深度解析正文、下载附件，操作比较耗时。
     */
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

            // 1. 解析基础信息 (与上面类似)
            String subject = (msg.getSubject() != null) ? MimeUtility.decodeText(msg.getSubject()) : "无标题";

            // 解析发件人
            String fromFull = "未知";
            if (msg.getFrom() != null && msg.getFrom().length > 0) {
                fromFull = MimeUtility.decodeText(msg.getFrom()[0].toString());
            }
            // ... (解析名字和地址逻辑同上)
            String fromName = fromFull;
            String fromAddress = "";
            if (fromFull.contains("<")) {
                fromName = fromFull.substring(0, fromFull.indexOf("<")).trim();
                fromAddress = fromFull.substring(fromFull.indexOf("<") + 1, fromFull.indexOf(">")).trim();
                if (fromName.isEmpty()) fromName = fromAddress;
            } else {
                fromAddress = fromFull;
            }

            // 2. 解析完整的收件人列表 (用于显示在详情页头部，比如 "收件人: 张三; 李四")
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
            if (recipientsStr.isEmpty()) recipientsStr = "未知";

            String sentDate = (msg.getSentDate() != null) ? new SimpleDateFormat("yyyy-MM-dd HH:mm").format(msg.getSentDate()) : "未知时间";

            // 3. 【核心】解析正文和附件 (递归解析 Multipart)
            // 调用下面的 parseMessage 递归方法
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
     * 移动邮件到垃圾箱 (增强版)
     * IMAP 协议中其实没有“移动”命令，所谓的移动其实是 "Copy" (复制) + "Delete" (删除原邮件)。
     * 策略：尝试复制到垃圾箱 -> 如果成功，标记原邮件删除 -> 如果复制失败，也强制标记原邮件删除。
     * 专门解决了 163 邮箱在复制到垃圾箱时可能报错的问题。
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
            sourceFolder.open(Folder.READ_WRITE); // 需要写权限来打删除标记 (Flags.Flag.DELETED)

            // 2. 获取目标垃圾箱
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
                        // 很多时候服务器只是拒绝复制，但我们仍需删除原邮件
                        System.err.println("⚠️ 警告：无法移动到垃圾箱 (将执行强制删除): " + e.getMessage());
                        copySuccess = false;
                    }
                }

                // 【核心逻辑】无论复制是否成功，只要用户点了删除，就在原文件夹标记删除！
                // 这样能保证收件箱里的邮件一定会被删掉，解决了"删不掉"的问题。
                msg.setFlag(Flags.Flag.DELETED, true);
            }

            // 4. 物理清除 (提交删除操作，EXPUNGE 指令)
            sourceFolder.expunge();

        } catch (Exception e) {
            System.err.println("❌ 删除流程严重错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            closeQuietly(trashFolder, null);
            closeQuietly(sourceFolder, store);
        }
    }

    /**
     * 彻底删除邮件 (不进垃圾箱，直接消失)
     */
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
                msg.setFlag(Flags.Flag.DELETED, true); // 标记删除
            }
            folder.expunge(); // 执行物理删除
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closeQuietly(folder, store);
        }
    }

    /**
     * 兼容旧接口：发送简单邮件
     */
    public void sendMailWithAttachment(UserAccount user, String to, String subject, String content,
                                       org.springframework.web.multipart.MultipartFile file) {
        // 兼容旧调用：非回复场景
        sendMailWithAttachment(user, to, subject, content, file, null, null);
    }

    /**
     * 核心发送邮件方法 (SMTP)
     * 支持群发、上传新附件、以及回复时自动带上旧附件。
     */
    public void sendMailWithAttachment(UserAccount user, String to, String subject, String content,
                                       org.springframework.web.multipart.MultipartFile file,
                                       String replyFolder, Long replyUid) {
        try {
            // 创建发送器 (JavaMailSenderImpl)
            JavaMailSenderImpl sender = createSender(user);
            // 创建 MIME 消息 (支持 HTML 和附件)
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            // 使用 Helper 类简化设置
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(
                    message, true, "UTF-8");

            helper.setFrom(user.getEmail());

            // ==================== 【多收件人处理】 ====================
            // 使用正则分割：分号(;)、逗号(,)、中文逗号(，) 均可作为分隔符
            // 这样用户输入 "a@q.com; b@q.com" 也能正常识别
            if (to != null && !to.isEmpty()) {
                String[] recipients = to.split("[,;，\\s]+");
                helper.setTo(recipients);
            }

            helper.setSubject(subject);
            helper.setText(content, true); // true 表示支持 HTML 格式

            // 1) 处理用户上传的新附件
            if (file != null && !file.isEmpty()) {
                helper.addAttachment(file.getOriginalFilename(), file);
            }

            // 2) 回复/转发时，自动读取本地保存的原邮件附件并添加
            // 因为浏览器安全限制，无法自动把旧文件填入 file input，所以需要在后端根据 ID 找回旧文件
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

            // 执行发送 (SMTP 协议)
            sender.send(message);
            // 发送成功后，手动保存副本到“已发送”文件夹 (因为 SMTP 协议只负责发，不负责存)
            saveToSentFolder(user, message);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("发送失败：" + e.getMessage());
        }
    }

    /**
     * 转发邮件
     * 自动构建引用格式的 HTML 正文
     */
    public void forwardMail(UserAccount user, String folder, Long originalUid, String targetEmail, String userComment) {
        try {
            EmailInfo original = getEmailDetail(user, folder, originalUid);
            if (original == null)
                throw new RuntimeException("原邮件加载失败");

            String subject = "Fwd: " + original.getTitle();

            // 构建 HTML 内容
            StringBuilder contentBuilder = new StringBuilder();
            // 1. 用户的留言 (放在最上面)
            if (userComment != null && !userComment.isEmpty()) {
                contentBuilder.append("<div style='margin-bottom: 20px; font-size: 14px;'>")
                        .append(userComment.replace("\n", "<br>")).append("</div>");
            }

            // 2. 原始邮件引用头 (模仿 Outlook 样式)
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
            contentBuilder.append(original.getContent()); // 原邮件正文

            // 创建发送器
            JavaMailSenderImpl sender = createSender(user);
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(
                    message, true, "UTF-8");
            helper.setFrom(user.getEmail());
            helper.setTo(targetEmail);
            helper.setSubject(subject);
            helper.setText(contentBuilder.toString(), true);

            // 添加原附件
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

    /**
     * 将发送出去的邮件保存到“已发送”文件夹
     */
    private void saveToSentFolder(UserAccount user, jakarta.mail.internet.MimeMessage message) {
        Store store = null;
        Folder sentFolder = null;
        try {
            store = getImapStore(user);
            String sentName = getCorrectFolderName(user.getType(), "已发送");
            sentFolder = store.getFolder(sentName);

            // 如果没找到，尝试 fallback 到标准名称
            if (!sentFolder.exists())
                sentFolder = store.getFolder("Sent Messages");

            if (sentFolder.exists()) {
                sentFolder.open(Folder.READ_WRITE);
                message.setFlag(Flags.Flag.SEEN, true); // 标记为已读
                sentFolder.appendMessages(new Message[] { message }); // IMAP 命令：追加到文件夹
            }
        } catch (Exception e) {
            // 保存副本失败不影响发送成功，所以这里吞掉异常
        } finally {
            closeQuietly(sentFolder, store);
        }
    }


    /**
     * 【文件夹名称映射核心】
     * 解决不同邮件服务商对系统文件夹命名不一致的问题。
     * QQ 使用英文 ("Sent Messages"), 163 使用中文 ("已发送")。
     */
    private String getCorrectFolderName(String mailType, String uiFolderName) {
        // 1. 收件箱：所有邮箱统一叫 INBOX
        if ("收件箱".equals(uiFolderName)) {
            return "INBOX";
        }

        // 2. QQ 邮箱映射
        if ("qq".equals(mailType)) {
            if ("已发送".equals(uiFolderName)) return "Sent Messages";
            if ("已删除".equals(uiFolderName)) return "Deleted Messages";
            if ("草稿箱".equals(uiFolderName)) return "Drafts";
            if ("垃圾箱".equals(uiFolderName)) return "Junk";
        }

        // 3. 163 邮箱映射
        if ("163".equals(mailType)) {
            if ("已发送".equals(uiFolderName)) return "已发送";
            if ("已删除".equals(uiFolderName)) return "已删除";
            if ("草稿箱".equals(uiFolderName)) return "草稿箱";
            if ("垃圾箱".equals(uiFolderName)) return "垃圾邮件";
        }

        // 4. HUST (Coremail 系统)
        if ("hust".equals(mailType)) {
            if ("已发送".equals(uiFolderName)) return "Sent Items"; // Coremail 常见英文名
            if ("已删除".equals(uiFolderName)) return "Trash";
            if ("草稿箱".equals(uiFolderName)) return "Drafts";
            if ("垃圾箱".equals(uiFolderName)) return "Junk E-mail";
        }

        // 4. 其他情况返回原名，假设用户建立的自定义文件夹
        return uiFolderName;
    }

    /**
     * 创建 SMTP 发送器
     */
    private JavaMailSenderImpl createSender(UserAccount user) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(user.getSmtpHost());
        sender.setPort(user.getSmtpPort()); // 通常是 465
        sender.setUsername(user.getEmail());
        sender.setPassword(user.getPassword());
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.trust", "*");

        // 【修复】163、QQ 和 HUST 使用 465 端口时，都必须开启 SSL 才能连接
        if ("qq".equals(user.getType()) || "163".equals(user.getType()) || "hust".equals(user.getType())) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        return sender;
    }



    /**
     * 【复杂逻辑】递归解析邮件内容（增强健壮性版）
     * 邮件结构像树一样（MIME树），需要递归遍历。
     * 1. 智能处理 multipart/alternative，优先取 HTML，避免正文重复。
     * 2. 纯文本转 HTML，解决换行丢失问题。
     * 3. 附件文件名清理，防止路径注入。
     */
    private void parseMessage(Part part, StringBuilder bodyText, List<String> attachments) throws Exception {
        // Case 1. 处理纯文本：将换行符转换为 HTML 样式
        if (part.isMimeType("text/plain")) {
            String txt = part.getContent().toString();
            if (txt == null) txt = "";
            // 使用 CSS white-space: pre-wrap 保留原格式，比单纯替换 <br> 效果更好
            bodyText.append("<div style='font-family: sans-serif; white-space: pre-wrap;'>")
                    .append(txt)
                    .append("</div>");
        }
        // Case 2. 处理 HTML：直接拼接
        else if (part.isMimeType("text/html")) {
            bodyText.append(part.getContent().toString());
        }
        // Case 3. 处理多部分内容 (Multipart 容器)
        else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();

            // --- 核心修复：针对 multipart/alternative (多重选择) 的特殊处理 ---
            // 这种类型通常同时包含纯文本版和HTML版，我们只取一个，否则显示会重复
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
                    // 极端情况：都没有，那就按默认逻辑全部遍历
                    for (int i = 0; i < multipart.getCount(); i++) {
                        parseMessage(multipart.getBodyPart(i), bodyText, attachments);
                    }
                }
            } else {
                // --- 对于 multipart/mixed (包含附件)，递归遍历所有部分 ---
                for (int i = 0; i < multipart.getCount(); i++) {
                    parseMessage(multipart.getBodyPart(i), bodyText, attachments);
                }
            }
        }
        // Case 4. 处理附件
        else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) ||
                (part.getFileName() != null && !part.getFileName().isEmpty())) {

            String fileName = MimeUtility.decodeText(part.getFileName());

            // 【安全修复】：提取纯文件名，防止文件名包含路径导致保存错误或安全漏洞
            // 比如黑客发来的文件名是 "../../windows/system32/cmd.exe"
            if (fileName.contains("/") || fileName.contains("\\")) {
                int lastIndex = Math.max(fileName.lastIndexOf("/"), fileName.lastIndexOf("\\"));
                fileName = fileName.substring(lastIndex + 1);
            }

            File saveDir = new File(SAVE_PATH);
            if (!saveDir.exists()) saveDir.mkdirs();

            try (InputStream is = part.getInputStream()) {
                // 保存文件到硬盘
                Files.copy(is, new File(SAVE_PATH + fileName).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            attachments.add(fileName); // 将文件名加入列表返回给前端
        }
    }

    /**
     * 安静地关闭资源，吞掉异常
     * 防止连接泄露导致服务器崩溃
     */
    private void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen())
                folder.close(false); // false 表示不执行 EXPUNGE (物理删除)
        } catch (Exception e) {
        }
        try {
            if (store != null)
                store.close();
        } catch (Exception e) {
        }
    }

    /**
     * 创建自定义文件夹
     */
    public void createFolder(UserAccount user, String folderName) throws Exception {
        Store store = null;
        try {
            store = getImapStore(user);

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
     * 删除自定义文件夹
     */
    public void deleteFolder(UserAccount user, String folderName) throws Exception {
        // 安全检查：禁止删除系统文件夹
        List<String> systemFolders = Arrays.asList("INBOX", "收件箱", "Sent", "已发送", "Drafts", "草稿箱", "Trash", "已删除", "Junk", "垃圾箱");
        if (systemFolders.stream().anyMatch(s -> s.equalsIgnoreCase(folderName))) {
            throw new RuntimeException("系统文件夹不能删除");
        }

        Store store = null;
        try {
            store = getImapStore(user);
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
     * 移动邮件 (复制 + 删除)
     */
    public void moveMessage(UserAccount user, String fromFolder, String toFolder, long uid) throws Exception {
        Store store = null;
        Folder source = null;
        Folder target = null;
        try {
            store = getImapStore(user);

            // 打开源文件夹
            String realSource = getCorrectFolderName(user.getType(), fromFolder);
            source = store.getFolder(realSource);
            source.open(Folder.READ_WRITE);

            // 打开目标文件夹
            String realTarget = getCorrectFolderName(user.getType(), toFolder);
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
            closeQuietly(target, null);
            closeQuietly(source, store);
        }
    }

    // ================== 核心通用方法 ==================

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

        // --- 核心：统一发送 IMAP ID (解决 163 报错 "Unsafe Login" 问题) ---
        // 163 邮箱强制要求客户端发送 ID 信息，否则会拒绝部分操作
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

    /**
     * 【调试专用】获取服务器上所有的文件夹名称列表
     * 当你发现“收件箱”或“已发送”是空的时候，调用这个接口看看服务器到底叫什么名字。
     */
    public List<String> getAllFolders(UserAccount user) {
        List<String> folderNames = new ArrayList<>();
        Store store = null;
        try {
            store = getImapStore(user); // 复用之前的连接方法
            Folder defaultFolder = store.getDefaultFolder();

            // list("*") 表示列出所有层级的文件夹
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
}