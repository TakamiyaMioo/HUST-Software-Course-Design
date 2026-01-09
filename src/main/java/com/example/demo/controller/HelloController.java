package com.example.demo.controller;

import com.example.demo.entity.AppUser;
import com.example.demo.entity.Contact;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class HelloController {

    @Autowired
    private MailService mailService;
    @Autowired
    private ContactRepository contactRepository;
    @Autowired
    private SentLogRepository sentLogRepository;
    @Autowired
    private DraftRepository draftRepository;

    // --- 新增注入 ---
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private EmailAccountRepository emailAccountRepository;

    // ================== 1. 认证与注册模块 (大幅修改) ==================

    @GetMapping("/")
    public String root() {
        return "login";
    }

    // 注册页面
    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    // 执行注册
    @PostMapping("/register")
    public String doRegister(@RequestParam String username,
                             @RequestParam String password,
                             @RequestParam String nickname,
                             Model model) {
        // 检查用户名是否存在
        if (appUserRepository.findByUsername(username) != null) {
            model.addAttribute("error", "用户名已存在");
            return "login"; // 这里简单处理，实际可返回 register 并带错误
        }

        AppUser newUser = new AppUser(username, password, nickname);
        appUserRepository.save(newUser);
        return "redirect:/"; // 注册成功，去登录
    }

    // 登录逻辑 (核心修改)
    @PostMapping("/login")
    public String login(@RequestParam String username,
                        @RequestParam String password,
                        HttpSession session,
                        Model model) {

        // A. 验证主账号 (查询数据库)
        AppUser appUser = appUserRepository.findByUsername(username);
        if (appUser == null || !appUser.getPassword().equals(password)) {
            model.addAttribute("error", "用户名或密码错误");
            return "login";
        }

        // B. 主账号登录成功
        session.setAttribute("appUser", appUser);

        // C. 尝试加载该用户的邮箱账号
        List<EmailAccount> accounts = emailAccountRepository.findByAppUserId(appUser.getId());

        if (!accounts.isEmpty()) {
            // 如果有邮箱，默认激活第一个
            activateEmailAccount(session, accounts.get(0));
            return "redirect:/inbox";
        } else {
            // D. 如果没有绑定邮箱，跳转到设置页去绑定
            // 注意：此时 session 中没有 "currentUser"，只有 "appUser"
            return "redirect:/settings";
        }
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate(); // 清除所有 session 信息
        return "redirect:/";
    }

    /**
     * 辅助方法：激活指定的邮箱账号
     * 将 EmailAccount 转换为旧版 UserAccount 并存入 Session，兼容原有 MailService
     */
    private void activateEmailAccount(HttpSession session, EmailAccount account) {
        // 1. 解密授权码
        String realAuthCode = AESUtil.decrypt(account.getPassword());

        // 2. 构造适配旧代码的对象
        UserAccount userAccount = new UserAccount(account.getEmail(), realAuthCode, account.getType());

        // 3. 设置 Session
        session.setAttribute("currentUser", userAccount); // 供 MailService 使用
        session.setAttribute("currentEmailId", account.getId()); // 供后续数据库查询使用
    }


    // ================== 2. 邮件列表视图 (原有逻辑微调) ==================
// 1. 收件箱 (通用文件夹视图)
    @GetMapping("/inbox")
    public ModelAndView inbox(HttpSession session,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "date") String sort,
                              @RequestParam(defaultValue = "desc") String order,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(defaultValue = "all") String searchType,
                              @RequestParam(required = false) String folder) { // 【新增参数】

        if (session.getAttribute("appUser") == null) return new ModelAndView("redirect:/");
        if (session.getAttribute("currentUser") == null) return new ModelAndView("redirect:/settings");

        // 【核心修改】如果前端传了 folder 就用，没传就默认 "收件箱"
        String targetFolder = StringUtils.hasText(folder) ? folder : "收件箱";

        return getFolderView(session, targetFolder, "/inbox", page, sort, order, keyword, searchType);
    }

    // 2. 已发送
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

    // 3. 已删除
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
     * 公共方法：构建文件夹视图
     */
    private ModelAndView getFolderView(HttpSession session, String folderName, String baseUrl,
                                       int page, String sort, String order, String keyword, String searchType) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        // 双重保险
        if (user == null) return new ModelAndView("redirect:/settings");

        ModelAndView mav = new ModelAndView("inbox");

        // 调用 Service 获取邮件
        Map<String, Object> result = mailService.receiveEmails(user, folderName, page, 10, sort, order, keyword, searchType);

        mav.addObject("emails", result.get("list"));
        mav.addObject("currentFolder", folderName);

        int totalCount = (int) result.get("totalCount");
        int totalPages = (int) Math.ceil((double) totalCount / 10);
        mav.addObject("currentPage", page);
        mav.addObject("totalPages", totalPages);
        mav.addObject("baseUrl", baseUrl);

        mav.addObject("sort", sort);
        mav.addObject("order", order);
        mav.addObject("keyword", keyword);
        mav.addObject("searchType", searchType);

        // 注意：这里的 findAll 还是查的所有联系人，后续步骤需要改为查当前用户的联系人
        mav.addObject("contacts", contactRepository.findAll());

        return mav;
    }


    // ================== 3. 邮件操作 (保持不变，依赖 currentUser) ==================

    @GetMapping("/email/detail")
    @ResponseBody
    public Map<String, Object> getEmailDetail(@RequestParam String folder, @RequestParam Long uid, HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        Map<String, Object> resp = new HashMap<>();
        if (user == null) {
            resp.put("error", "未登录或未绑定邮箱");
            return resp;
        }
        EmailInfo detail = mailService.getEmailDetail(user, folder, uid);
        if (detail != null) {
            resp.put("content", detail.getContent());
            resp.put("files", detail.getFilenames());
            resp.put("success", true);
        } else {
            resp.put("error", "加载失败");
        }
        return resp;
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadFile(@RequestParam String filename) {
        try {
            Path path = Paths.get(MailService.SAVE_PATH + filename);
            Resource resource = new UrlResource(path.toUri());
            if (resource.exists() || resource.isReadable()) {
                String encodedFileName = URLEncoder.encode(resource.getFilename(), StandardCharsets.UTF_8.toString());
                encodedFileName = encodedFileName.replaceAll("\\+", "%20");
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

    @GetMapping("/sendPage")
    public ModelAndView sendPage(HttpSession session,
                                 @RequestParam(required = false) Long draftId,
                                 @RequestParam(required = false) Long replyUid,
                                 @RequestParam(required = false) String folder) {

        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        // 如果没绑邮箱，不能写信
        if (user == null) return new ModelAndView("redirect:/settings");

        ModelAndView mav = new ModelAndView("send");
        mav.addObject("contacts", contactRepository.findAll());
        mav.addObject("currentFolder", "写信");

        if (draftId != null) {
            draftRepository.findById(draftId).ifPresent(draft -> {
                mav.addObject("draftReceiver", draft.getReceiver());
                mav.addObject("draftTitle", draft.getTitle());
                mav.addObject("draftContent", draft.getContent());
            });
            return mav;
        }

        if (replyUid != null && folder != null) {
            EmailInfo originalEmail = mailService.getEmailDetail(user, folder, replyUid);
            if (originalEmail != null) {
                mav.addObject("draftReceiver", originalEmail.getAddress());
                mav.addObject("draftTitle", "Re: " + originalEmail.getTitle());
                mav.addObject("replyUid", replyUid);
                mav.addObject("replyFolder", folder);
            }
        }
        return mav;
    }

    @PostMapping("/sendMail")
    public ModelAndView sendMail(@RequestParam String to,
                                 @RequestParam String subject,
                                 @RequestParam String text,
                                 @RequestParam(value = "file", required = false) MultipartFile file,
                                 @RequestParam(value = "replyUid", required = false) Long replyUid,
                                 @RequestParam(value = "replyFolder", required = false) String replyFolder,
                                 HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) return new ModelAndView("redirect:/settings");

        mailService.sendMailWithAttachment(user, to, subject, text, file, replyFolder, replyUid);

        // 注意：这里后续也需要加上 emailAccountId
        sentLogRepository.save(new SentLog(to, subject, text));
        return new ModelAndView("redirect:/inbox");
    }

    @PostMapping("/mail/forward")
    @ResponseBody
    public Map<String, Object> forwardMail(@RequestParam String folder,
                                           @RequestParam Long uid,
                                           @RequestParam String to,
                                           @RequestParam(required = false) String comment,
                                           HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        Map<String, Object> resp = new HashMap<>();
        if (user == null) {
            resp.put("success", false);
            resp.put("error", "登录已过期或未绑定邮箱");
            return resp;
        }
        try {
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

    @PostMapping("/saveDraft")
    public String saveDraft(@RequestParam(required = false) String to,
                            @RequestParam(required = false) String subject,
                            @RequestParam(required = false) String text,
                            HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) return "redirect:/settings";

        // 注意：这里后续也需要加上 emailAccountId
        draftRepository.save(new com.example.demo.entity.DraftEmail(user.getEmail(), to, subject, text));
        return "redirect:/drafts";
    }

    @GetMapping("/drafts")
    public ModelAndView draftBox(HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) return new ModelAndView("redirect:/settings");

        ModelAndView mav = new ModelAndView("drafts");
        // 注意：这里后续需要改为根据 AppUserId 或 EmailAccountId 查询
        List<com.example.demo.entity.DraftEmail> drafts = draftRepository.findBySender(user.getEmail());
        mav.addObject("drafts", drafts);
        mav.addObject("currentFolder", "草稿箱");
        return mav;
    }

    @GetMapping("/deleteDraft")
    public String deleteDraft(@RequestParam Long id) {
        draftRepository.deleteById(id);
        return "redirect:/drafts";
    }

    @GetMapping("/deleteFromInbox")
    public String deleteFromInbox(HttpSession session, @RequestParam Long id) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user != null)
            mailService.moveToTrash(user, "收件箱", id);
        return "redirect:/inbox";
    }

    @GetMapping("/deleteFromSent")
    public String deleteFromSent(HttpSession session, @RequestParam Long id) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user != null)
            mailService.moveToTrash(user, "已发送", id);
        return "redirect:/sent";
    }

    @GetMapping("/deleteForever")
    public String deleteForever(HttpSession session, @RequestParam Long id) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user != null)
            mailService.deleteMessage(user, "已删除", id);
        return "redirect:/trash";
    }

    // ================== 5. 设置页 (修改权限判断) ==================

    @GetMapping("/settings")
    public String settingsPage(HttpSession session, Model model) {
        // 修改点：只要登录了主账号 (appUser)，就可以进设置页，不需要绑定邮箱 (currentUser)
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) {
            return "redirect:/";
        }

        // 我们顺便把当前用户的邮箱列表查出来，传给前端（为下一步做准备）
        List<EmailAccount> accounts = emailAccountRepository.findByAppUserId(appUser.getId());
        model.addAttribute("accounts", accounts);

        model.addAttribute("currentFolder", "设置");
        return "settings";
    }

    // ================== 6. 邮箱账号管理 (新增模块) ==================

    /**
     * 添加/绑定新邮箱
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

        // 1. 检查该邮箱是否已被绑定 (可选：防止重复绑定)
        // 这里为了简单，暂不强校验，或者你可以加一个 findByEmail 判断

        // 2. 加密授权码 (非常重要！)
        String encryptedPwd = AESUtil.encrypt(password);

        // 3. 保存到数据库
        EmailAccount newAccount = new EmailAccount(email, encryptedPwd, type, appUser.getId());
        // 如果想存别名，可以在 Entity 里加个 alias 字段，这里先略过
        emailAccountRepository.save(newAccount);

        // 4. 如果这是用户的第一个邮箱，自动激活它，这样用户马上就能用
        if (session.getAttribute("currentUser") == null) {
            activateEmailAccount(session, newAccount);
        }

        return "redirect:/settings";
    }

    /**
     * 删除/解绑邮箱
     */
    @GetMapping("/account/delete")
    public String deleteAccount(@RequestParam Long id, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        // 1. 安全检查：确保删除的是自己的邮箱
        emailAccountRepository.findById(id).ifPresent(account -> {
            if (account.getAppUserId().equals(appUser.getId())) {
                emailAccountRepository.delete(account);

                // 2. 如果删除的是当前正在用的邮箱，清理 Session
                Long currentId = (Long) session.getAttribute("currentEmailId");
                if (currentId != null && currentId.equals(id)) {
                    session.removeAttribute("currentUser");
                    session.removeAttribute("currentEmailId");

                    // 尝试切换到剩余的另一个邮箱
                    List<EmailAccount> others = emailAccountRepository.findByAppUserId(appUser.getId());
                    if (!others.isEmpty()) {
                        activateEmailAccount(session, others.get(0));
                    }
                }
            }
        });

        return "redirect:/settings";
    }

    // ================== 7. 账号切换与全局数据 (新增模块) ==================

    /**
     * 全局数据注入：
     * 被 @ModelAttribute 注解的方法会在每个 @GetMapping/@PostMapping 执行前运行。
     * 这样我们就不需要在 inbox, sent, draft 等每个方法里都去查一遍 "accounts" 了。
     */
    @ModelAttribute
    public void addGlobalAttributes(Model model, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser != null) {
            // 1. 邮箱账号列表
            List<EmailAccount> myAccounts = emailAccountRepository.findByAppUserId(appUser.getId());
            model.addAttribute("myAccounts", myAccounts);

            Long currentId = (Long) session.getAttribute("currentEmailId");
            model.addAttribute("currentEmailId", currentId);

            if (currentId != null) {
                myAccounts.stream()
                        .filter(a -> a.getId().equals(currentId))
                        .findFirst()
                        .ifPresent(acc -> model.addAttribute("currentAccount", acc));
            }

            // ================== 【新增】加载自定义文件夹 ==================
            UserAccount currentUser = (UserAccount) session.getAttribute("currentUser");
            if (currentUser != null) {
                // 调用 Service 获取文件夹
                // 注意：这会产生一次网络请求，稍微影响一点点加载速度，但为了实时性是值得的
                List<String> folders = mailService.getCustomFolders(currentUser);
                model.addAttribute("customFolders", folders);
            }
            // ==========================================================
        }
    }

    /**
     * 切换当前使用的邮箱
     */
    @GetMapping("/account/switch")
    public String switchAccount(@RequestParam Long id, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        // 验证这个邮箱确实是属于当前登录用户的 (防止恶意 ID 攻击)
        emailAccountRepository.findById(id).ifPresent(account -> {
            if (account.getAppUserId().equals(appUser.getId())) {
                // 激活这个邮箱
                activateEmailAccount(session, account);
            }
        });

        return "redirect:/inbox"; // 切换完直接跳回收件箱
    }
}