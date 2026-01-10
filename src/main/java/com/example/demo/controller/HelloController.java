package com.example.demo.controller;

import com.example.demo.entity.AppUser;
import com.example.demo.entity.EmailAccount;
import com.example.demo.entity.SentLog;
import com.example.demo.model.EmailInfo;
import com.example.demo.model.UserAccount;
import com.example.demo.repository.*;
import com.example.demo.service.MailService;
import com.example.demo.utils.AESUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 主控制器 (Controller)
 * 负责处理前端的所有 HTTP 请求，包括页面跳转、表单提交、AJAX 数据获取等。
 * 它是前端视图 (View) 和后端逻辑 (Service/Repository) 之间的桥梁。
 */
@Controller
public class HelloController {

    // 注入核心邮件服务，用于收发邮件逻辑
    @Autowired
    private MailService mailService;
    // 注入联系人数据库操作接口
    @Autowired
    private ContactRepository contactRepository;
    // 注入发送日志数据库操作接口
    @Autowired
    private SentLogRepository sentLogRepository;
    // 注入草稿箱数据库操作接口
    @Autowired
    private DraftRepository draftRepository;

    // 注入应用用户（登录账号）数据库接口
    @Autowired
    private AppUserRepository appUserRepository;
    // 注入邮箱账号（绑定的外部邮箱）数据库接口
    @Autowired
    private EmailAccountRepository emailAccountRepository;

    // 在 HelloController 类内部最上方添加
    // 定义用户头像存储的物理路径：项目根目录/data/avatars/
    // 使用 System.getProperty("user.dir") 获取当前运行目录，确保跨平台兼容
    private static final String AVATAR_STORE_PATH = System.getProperty("user.dir") + File.separator + "data"
            + File.separator + "avatars" + File.separator;

    // ================== 1. 认证与注册模块 ==================

    /**
     * 根路径请求，默认跳转到登录页
     */
    @GetMapping("/")
    public String root() {
        return "login";
    }

    /**
     * 跳转到注册页面
     */
    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    /**
     * 处理注册表单提交
     * @param username 用户名
     * @param password 密码
     * @param nickname 昵称
     * @param model 用于回传数据给页面（如错误信息）
     */
    @PostMapping("/register")
    public String doRegister(@RequestParam String username,
                             @RequestParam String password,
                             @RequestParam String nickname,
                             Model model) {
        // 检查用户名是否已存在
        if (appUserRepository.findByUsername(username) != null) {
            model.addAttribute("error", "用户名已存在");
            return "login"; // 注册失败，返回登录页并显示错误
        }

        // 创建新用户并保存到数据库
        AppUser newUser = new AppUser(username, password, nickname);
        appUserRepository.save(newUser);
        return "redirect:/"; // 注册成功，重定向到登录页
    }

    /**
     * 处理登录请求
     * @param username 用户名
     * @param password 密码
     * @param session HTTP会话，用于保存用户登录状态
     */
    @PostMapping("/login")
    public String login(@RequestParam String username,
                        @RequestParam String password,
                        HttpSession session,
                        Model model) {
        // 从数据库查找用户
        AppUser appUser = appUserRepository.findByUsername(username);
        // 校验用户是否存在以及密码是否匹配
        if (appUser == null || !appUser.getPassword().equals(password)) {
            model.addAttribute("error", "用户名或密码错误");
            return "login";
        }

        // 登录成功，将用户信息存入 Session
        session.setAttribute("appUser", appUser);

        // 查找该用户绑定的所有邮箱账号
        List<EmailAccount> accounts = emailAccountRepository.findByAppUserId(appUser.getId());

        if (!accounts.isEmpty()) {
            // 如果有绑定的邮箱，自动激活（登录）第一个邮箱账号
            activateEmailAccount(session, accounts.get(0));
            return "redirect:/inbox"; // 进入收件箱
        } else {
            // 如果没有绑定邮箱，跳转到设置页去绑定
            return "redirect:/settings";
        }
    }

    /**
     * 退出登录，销毁 Session
     */
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate(); // 清除所有 Session 数据
        return "redirect:/";
    }

    /**
     * 辅助方法：激活指定的邮箱账号
     * 将邮箱账号解密并存入 Session，供 MailService 使用
     */
    private void activateEmailAccount(HttpSession session, EmailAccount account) {
        // 解密数据库中存储的邮箱授权码/密码
        String realAuthCode = AESUtil.decrypt(account.getPassword());
        // 创建用于 JavaMail 连接的 UserAccount 对象
        UserAccount userAccount = new UserAccount(account.getEmail(), realAuthCode, account.getType());

        // 存入 Session，标记当前正在使用的邮箱身份
        session.setAttribute("currentUser", userAccount);
        // 存入当前选中的数据库ID，用于前端高亮显示
        session.setAttribute("currentEmailId", account.getId());
    }


    // ================== 2. 邮件列表视图 ==================

    /**
     * 显示收件箱页面
     * 支持分页、排序、搜索
     */
    @GetMapping("/inbox")
    public ModelAndView inbox(HttpSession session,
                              @RequestParam(defaultValue = "1") int page, // 当前页码
                              @RequestParam(defaultValue = "date") String sort, // 排序字段
                              @RequestParam(defaultValue = "desc") String order, // 排序方式
                              @RequestParam(required = false) String keyword, // 搜索关键词
                              @RequestParam(defaultValue = "all") String searchType, // 搜索类型
                              @RequestParam(required = false) String folder) { // 自定义文件夹名称

        // 登录与绑定检查
        if (session.getAttribute("appUser") == null) return new ModelAndView("redirect:/");
        if (session.getAttribute("currentUser") == null) return new ModelAndView("redirect:/settings");

        // 默认为"收件箱"，如果参数指定了文件夹则使用参数
        String targetFolder = StringUtils.hasText(folder) ? folder : "收件箱";
        // 调用通用方法获取邮件列表
        return getFolderView(session, targetFolder, "/inbox", page, sort, order, keyword, searchType);
    }

    /**
     * 显示已发送页面
     */
    @GetMapping("/sent")
    public ModelAndView sentBox(HttpSession session,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "date") String sort,
                                @RequestParam(defaultValue = "desc") String order,
                                @RequestParam(required = false) String keyword,
                                @RequestParam(defaultValue = "all") String searchType) {
        if (session.getAttribute("appUser") == null) return new ModelAndView("redirect:/");
        if (session.getAttribute("currentUser") == null) return new ModelAndView("redirect:/settings");

        return getFolderView(session, "已发送", "/sent", page, sort, order, keyword, searchType);
    }

    /**
     * 显示垃圾箱（已删除）页面
     */
    @GetMapping("/trash")
    public ModelAndView trashBox(HttpSession session,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "date") String sort,
                                 @RequestParam(defaultValue = "desc") String order,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(defaultValue = "all") String searchType) {
        if (session.getAttribute("appUser") == null) return new ModelAndView("redirect:/");
        if (session.getAttribute("currentUser") == null) return new ModelAndView("redirect:/settings");

        return getFolderView(session, "已删除", "/trash", page, sort, order, keyword, searchType);
    }

    /**
     * 私有辅助方法：通用的文件夹视图构建逻辑
     * 封装了调用 Service 获取邮件、计算分页、组装 ModelAndView 的重复代码
     */
    private ModelAndView getFolderView(HttpSession session, String folderName, String baseUrl,
                                       int page, String sort, String order, String keyword, String searchType) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) return new ModelAndView("redirect:/settings");

        ModelAndView mav = new ModelAndView("inbox"); // 使用 inbox.html 模板
        // 调用 Service 获取邮件数据（包含列表和总数）
        Map<String, Object> result = mailService.receiveEmails(user, folderName, page, 10, sort, order, keyword, searchType);

        // 将数据放入模型，供 Thymeleaf 渲染
        mav.addObject("emails", result.get("list"));
        mav.addObject("currentFolder", folderName);

        // 计算总页数
        int totalCount = (int) result.get("totalCount");
        int totalPages = (int) Math.ceil((double) totalCount / 10);
        mav.addObject("currentPage", page);
        mav.addObject("totalPages", totalPages);
        mav.addObject("baseUrl", baseUrl);

        // 回填搜索和排序参数，保证翻页时状态不丢失
        mav.addObject("sort", sort);
        mav.addObject("order", order);
        mav.addObject("keyword", keyword);
        mav.addObject("searchType", searchType);
        mav.addObject("contacts", contactRepository.findAll()); // 用于侧边栏联系人显示

        return mav;
    }


    // ================== 3. 邮件操作 ==================

    /**
     * 获取单封邮件的详细内容 (AJAX 接口)
     * 返回 JSON 数据
     */
    @GetMapping("/email/detail")
    @ResponseBody
    public Map<String, Object> getEmailDetail(@RequestParam String folder, @RequestParam Long uid, HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        Map<String, Object> resp = new HashMap<>();
        if (user == null) {
            resp.put("error", "未登录或未绑定邮箱");
            return resp;
        }
        // 调用 Service 获取详情
        EmailInfo detail = mailService.getEmailDetail(user, folder, uid);
        if (detail != null) {
            resp.put("content", detail.getContent()); // 邮件正文
            resp.put("files", detail.getFilenames()); // 附件列表
            resp.put("success", true);
        } else {
            resp.put("error", "加载失败");
        }
        return resp;
    }

    /**
     * 附件下载接口
     * @param filename 文件名（相对于保存路径）
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> downloadFile(@RequestParam String filename) {
        try {
            Path path = Paths.get(MailService.SAVE_PATH + filename);
            Resource resource = new UrlResource(path.toUri());
            if (resource.exists() || resource.isReadable()) {
                // 处理文件名编码，防止中文乱码
                String encodedFileName = URLEncoder.encode(resource.getFilename(), StandardCharsets.UTF_8.toString());
                encodedFileName = encodedFileName.replaceAll("\\+", "%20");

                // 返回文件流，设置 Content-Disposition 为 attachment 触发下载
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFileName + "\"")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(resource);
            } else {
                throw new RuntimeException("文件不存在");
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 附件预览接口 (如图片或PDF)
     * 在浏览器内直接打开而不是下载
     */
    @GetMapping("/preview")
    public ResponseEntity<Resource> previewFile(@RequestParam String filename) {
        try {
            if (filename.contains("..")) return ResponseEntity.badRequest().build(); // 安全检查
            Path path = Paths.get(MailService.SAVE_PATH + filename);
            Resource resource = new UrlResource(path.toUri());
            if (resource.exists() || resource.isReadable()) {
                // 探测文件类型 (MIME type)
                String contentType = Files.probeContentType(path);
                if (contentType == null) contentType = "application/octet-stream";

                // 设置 Content-Disposition 为 inline 触发预览
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                        .contentType(MediaType.parseMediaType(contentType))
                        .body(resource);
            } else {
                throw new RuntimeException("文件不存在");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 跳转到写信页面
     * 支持从草稿箱恢复或回复邮件时带入数据
     */
    @GetMapping("/sendPage")
    public ModelAndView sendPage(HttpSession session,
                                 @RequestParam(required = false) Long draftId, // 草稿ID
                                 @RequestParam(required = false) Long replyUid, // 回复的原邮件UID
                                 @RequestParam(required = false) String folder) { // 回复的原邮件文件夹

        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) return new ModelAndView("redirect:/settings");

        ModelAndView mav = new ModelAndView("send");
        mav.addObject("contacts", contactRepository.findAll());
        mav.addObject("currentFolder", "写信");

        // 如果是编辑草稿
        if (draftId != null) {
            draftRepository.findById(draftId).ifPresent(draft -> {
                mav.addObject("draftReceiver", draft.getReceiver());
                mav.addObject("draftTitle", draft.getTitle());
                mav.addObject("draftContent", draft.getContent());
            });
            return mav;
        }

        // 如果是回复邮件
        if (replyUid != null && folder != null) {
            EmailInfo originalEmail = mailService.getEmailDetail(user, folder, replyUid);
            if (originalEmail != null) {
                mav.addObject("draftReceiver", originalEmail.getAddress()); // 自动填入收件人
                mav.addObject("draftTitle", "Re: " + originalEmail.getTitle()); // 自动填入标题
                mav.addObject("replyUid", replyUid); // 标记这是一个回复操作
                mav.addObject("replyFolder", folder);
            }
        }
        return mav;
    }

    /**
     * 执行发送邮件操作
     */
    @PostMapping("/sendMail")
    public ModelAndView sendMail(@RequestParam String to,
                                 @RequestParam String subject,
                                 @RequestParam String text,
                                 @RequestParam(value = "file", required = false) MultipartFile file, // 附件
                                 @RequestParam(value = "replyUid", required = false) Long replyUid,
                                 @RequestParam(value = "replyFolder", required = false) String replyFolder,
                                 HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) return new ModelAndView("redirect:/settings");

        // 调用 Service 发送
        mailService.sendMailWithAttachment(user, to, subject, text, file, replyFolder, replyUid);
        // 保存发送日志
        sentLogRepository.save(new SentLog(to, subject, text));
        return new ModelAndView("redirect:/inbox");
    }

    /**
     * 转发邮件接口 (AJAX)
     */
    @PostMapping("/mail/forward")
    @ResponseBody
    public Map<String, Object> forwardMail(@RequestParam String folder,
                                           @RequestParam Long uid,
                                           @RequestParam String to,
                                           @RequestParam(required = false) String comment, // 转发附言
                                           HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        Map<String, Object> resp = new HashMap<>();
        if (user == null) {
            resp.put("success", false);
            resp.put("error", "登录已过期或未绑定邮箱");
            return resp;
        }
        try {
            // 执行转发逻辑
            mailService.forwardMail(user, folder, uid, to, comment);
            sentLogRepository.save(new SentLog(to, "Fwd: (转发邮件)", comment));
            resp.put("success", true);
        } catch (Exception e) {
            e.printStackTrace();
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return resp;
    }

    // ================== 4. 草稿与删除 ==================

    /**
     * 保存草稿
     */
    @PostMapping("/saveDraft")
    public String saveDraft(@RequestParam(required = false) String to,
                            @RequestParam(required = false) String subject,
                            @RequestParam(required = false) String text,
                            HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) return "redirect:/settings";

        draftRepository.save(new com.example.demo.entity.DraftEmail(user.getEmail(), to, subject, text));
        return "redirect:/drafts";
    }

    /**
     * 查看草稿箱
     */
    @GetMapping("/drafts")
    public ModelAndView draftBox(HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) return new ModelAndView("redirect:/settings");

        ModelAndView mav = new ModelAndView("drafts");
        List<com.example.demo.entity.DraftEmail> drafts = draftRepository.findBySender(user.getEmail());
        mav.addObject("drafts", drafts);
        mav.addObject("currentFolder", "草稿箱");
        return mav;
    }

    /**
     * 删除草稿
     */
    @GetMapping("/deleteDraft")
    public String deleteDraft(@RequestParam Long id) {
        draftRepository.deleteById(id);
        return "redirect:/drafts";
    }

    /**
     * 从收件箱删除 (移动到垃圾箱)
     */
    @GetMapping("/deleteFromInbox")
    public String deleteFromInbox(HttpSession session, @RequestParam Long id) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user != null)
            mailService.moveToTrash(user, "收件箱", id);
        return "redirect:/inbox";
    }

    /**
     * 从已发送删除 (移动到垃圾箱)
     */
    @GetMapping("/deleteFromSent")
    public String deleteFromSent(HttpSession session, @RequestParam Long id) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user != null)
            mailService.moveToTrash(user, "已发送", id);
        return "redirect:/sent";
    }

    /**
     * 永久删除 (从垃圾箱删除)
     */
    @GetMapping("/deleteForever")
    public String deleteForever(HttpSession session, @RequestParam Long id) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user != null)
            mailService.deleteMessage(user, "已删除", id);
        return "redirect:/trash";
    }

    // ================== 5. 设置页与账号管理 ==================

    /**
     * 设置页面：显示已绑定的邮箱列表
     */
    @GetMapping("/settings")
    public String settingsPage(HttpSession session, Model model) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) {
            return "redirect:/";
        }

        List<EmailAccount> accounts = emailAccountRepository.findByAppUserId(appUser.getId());
        model.addAttribute("accounts", accounts);

        model.addAttribute("currentFolder", "设置");
        return "settings";
    }

    // 修改密码接口
    @PostMapping("/settings/password")
    public String changePassword(@RequestParam String oldPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 HttpSession session,
                                 RedirectAttributes attributes) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        // 获取最新的用户数据
        AppUser currentUser = appUserRepository.findById(appUser.getId()).orElse(null);
        if (currentUser == null) return "redirect:/";

        // 验证旧密码
        if (!currentUser.getPassword().equals(oldPassword)) {
            attributes.addFlashAttribute("error", "旧密码错误，请重试");
            return "redirect:/settings";
        }
        // 验证两次新密码是否一致
        if (!newPassword.equals(confirmPassword)) {
            attributes.addFlashAttribute("error", "两次输入的新密码不一致");
            return "redirect:/settings";
        }

        // 保存新密码
        currentUser.setPassword(newPassword);
        appUserRepository.save(currentUser);
        session.setAttribute("appUser", currentUser);

        attributes.addFlashAttribute("success", "密码修改成功！下次登录请使用新密码。");
        return "redirect:/settings";
    }

    // 【新增】修改头像接口
    // 处理头像文件的上传、保存和URL更新
    @PostMapping("/settings/avatar")
    public String updateAvatar(@RequestParam("avatarFile") MultipartFile file,
                               HttpSession session,
                               RedirectAttributes attributes) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null)
            return "redirect:/";

        if (file.isEmpty()) {
            attributes.addFlashAttribute("error", "请选择要上传的图片");
            return "redirect:/settings";
        }

        try {
            // 1. 确保外部存储目录存在 (EXE运行目录/data/avatars/)
            File dir = new File(AVATAR_STORE_PATH);
            if (!dir.exists()) {
                dir.mkdirs(); // 自动创建多级目录
            }

            // 2. 生成唯一文件名，防止文件覆盖
            String originalFilename = file.getOriginalFilename();
            String suffix = ".jpg"; // 默认后缀
            if (originalFilename != null && originalFilename.contains(".")) {
                suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            // 使用 UUID 生成随机文件名
            String newFilename = UUID.randomUUID().toString() + suffix;

            // 3. 保存文件到外部文件夹 (硬盘IO操作)
            file.transferTo(new File(dir, newFilename));

            // 4. 更新用户信息
            AppUser currentUser = appUserRepository.findById(appUser.getId()).orElse(null);
            if (currentUser != null) {
                // 【关键】URL 指向我们刚才写的读取接口 (/avatar/show)，而不是静态资源路径
                // 这样可以读取项目 jar 包外部的文件
                String avatarUrl = "/avatar/show?filename=" + newFilename;

                currentUser.setAvatar(avatarUrl);
                appUserRepository.save(currentUser);

                // 更新 session，让页面立即刷新变化
                session.setAttribute("appUser", currentUser);
                attributes.addFlashAttribute("success", "头像修改成功！");
            }

        } catch (IOException e) {
            e.printStackTrace();
            attributes.addFlashAttribute("error", "头像上传失败：" + e.getMessage());
        }

        return "redirect:/settings";
    }

    /**
     * 绑定新的邮箱账号 (POP3/IMAP)
     */
    @PostMapping("/account/add")
    public String addAccount(@RequestParam String email,
                             @RequestParam String password,
                             @RequestParam String type,
                             @RequestParam(required = false) String alias,
                             HttpSession session,
                             Model model) {

        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        // 加密存储邮箱密码（授权码）
        String encryptedPwd = AESUtil.encrypt(password);
        EmailAccount newAccount = new EmailAccount(email, encryptedPwd, type, appUser.getId());
        emailAccountRepository.save(newAccount);

        // 如果还没激活任何邮箱，立即激活这一个
        if (session.getAttribute("currentUser") == null) {
            activateEmailAccount(session, newAccount);
        }

        return "redirect:/settings";
    }

    /**
     * 删除已绑定的邮箱账号
     */
    @GetMapping("/account/delete")
    public String deleteAccount(@RequestParam Long id, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        emailAccountRepository.findById(id).ifPresent(account -> {
            // 确保只能删除自己的账号
            if (account.getAppUserId().equals(appUser.getId())) {
                emailAccountRepository.delete(account);

                // 如果删除的是当前正在使用的账号
                Long currentId = (Long) session.getAttribute("currentEmailId");
                if (currentId != null && currentId.equals(id)) {
                    // 清除当前会话状态
                    session.removeAttribute("currentUser");
                    session.removeAttribute("currentEmailId");

                    // 尝试切换到剩余的另一个账号
                    List<EmailAccount> others = emailAccountRepository.findByAppUserId(appUser.getId());
                    if (!others.isEmpty()) {
                        activateEmailAccount(session, others.get(0));
                    }
                }
            }
        });

        return "redirect:/settings";
    }

    /**
     * 全局 Model 属性注入
     * 这里的代码会在每个 @GetMapping/@PostMapping 执行前运行
     * 用于向所有页面提供公共数据（如当前选中的账号、自定义文件夹列表等）
     */
    @ModelAttribute
    public void addGlobalAttributes(Model model, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser != null) {
            // 加载用户的邮箱列表
            List<EmailAccount> myAccounts = emailAccountRepository.findByAppUserId(appUser.getId());
            model.addAttribute("myAccounts", myAccounts);

            Long currentId = (Long) session.getAttribute("currentEmailId");
            model.addAttribute("currentEmailId", currentId);

            // 标记当前激活的账号对象
            if (currentId != null) {
                myAccounts.stream()
                        .filter(a -> a.getId().equals(currentId))
                        .findFirst()
                        .ifPresent(acc -> model.addAttribute("currentAccount", acc));
            }

            // 加载自定义文件夹
            UserAccount currentUser = (UserAccount) session.getAttribute("currentUser");
            if (currentUser != null) {
                List<String> folders = mailService.getCustomFolders(currentUser);
                model.addAttribute("customFolders", folders);
            }
        }
    }

    /**
     * 切换当前激活的邮箱账号
     */
    @GetMapping("/account/switch")
    public String switchAccount(@RequestParam Long id, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        emailAccountRepository.findById(id).ifPresent(account -> {
            if (account.getAppUserId().equals(appUser.getId())) {
                activateEmailAccount(session, account);
            }
        });
        return "redirect:/inbox";
    }

    // ... 在 HelloController 中添加以下方法 ...

    // 1. 创建自定义文件夹接口
    @PostMapping("/folder/add")
    @ResponseBody
    public Map<String, Object> addFolder(@RequestParam String folderName, HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        Map<String, Object> resp = new HashMap<>();
        if (user == null) {
            resp.put("success", false);
            resp.put("error", "未登录");
            return resp;
        }
        try {
            mailService.createFolder(user, folderName);
            resp.put("success", true);
        } catch (Exception e) {
            e.printStackTrace();
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return resp;
    }

    // 2. 删除自定义文件夹接口
    @PostMapping("/folder/delete")
    @ResponseBody
    public Map<String, Object> deleteFolder(@RequestParam String folderName, HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        Map<String, Object> resp = new HashMap<>();
        if (user == null) {
            resp.put("success", false);
            resp.put("error", "未登录");
            return resp;
        }
        try {
            mailService.deleteFolder(user, folderName);
            resp.put("success", true);
        } catch (Exception e) {
            e.printStackTrace();
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return resp;
    }

    // 3. 移动邮件接口 (如从收件箱移动到自定义文件夹)
    @PostMapping("/mail/move")
    @ResponseBody
    public Map<String, Object> moveMail(@RequestParam String fromFolder,
                                        @RequestParam String toFolder,
                                        @RequestParam Long uid,
                                        HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        Map<String, Object> resp = new HashMap<>();
        if (user == null) {
            resp.put("success", false);
            resp.put("error", "未登录");
            return resp;
        }
        try {
            mailService.moveMessage(user, fromFolder, toFolder, uid);
            resp.put("success", true);
        } catch (Exception e) {
            e.printStackTrace();
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        return resp;
    }

    /**
     * 检查并列出所有文件夹 (调试用)
     */
    @GetMapping("/checkFolders")
    @ResponseBody
    public List<String> checkFolders(HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) {
            return List.of("请先登录邮箱！");
        }
        return mailService.getAllFolders(user);
    }

    // 【新增】读取外部头像文件的接口
    // Spring Boot 默认不直接暴露项目外的文件，需要通过这个接口读取流并返回
    @GetMapping("/avatar/show")
    public ResponseEntity<Resource> showAvatar(@RequestParam String filename) {
        try {
            // 安全检查：防止路径遍历攻击 (例如文件名输入 ../../windows/system32 等)
            if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                return ResponseEntity.badRequest().build();
            }

            // 构建完整文件路径
            Path path = Paths.get(AVATAR_STORE_PATH + filename);
            Resource resource = new UrlResource(path.toUri());

            if (resource.exists() || resource.isReadable()) {
                // 简单的图片类型判断
                String contentType = "image/jpeg";
                if (filename.toLowerCase().endsWith(".png"))
                    contentType = "image/png";
                if (filename.toLowerCase().endsWith(".gif"))
                    contentType = "image/gif";

                // 返回图片流，浏览器可直接渲染
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}