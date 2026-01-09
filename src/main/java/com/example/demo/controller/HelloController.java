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

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private EmailAccountRepository emailAccountRepository;

    // ================== 1. 认证与注册模块 ==================

    @GetMapping("/")
    public String root() {
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String doRegister(@RequestParam String username,
                             @RequestParam String password,
                             @RequestParam String nickname,
                             Model model) {
        if (appUserRepository.findByUsername(username) != null) {
            model.addAttribute("error", "用户名已存在");
            return "login"; 
        }

        AppUser newUser = new AppUser(username, password, nickname);
        appUserRepository.save(newUser);
        return "redirect:/";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username,
                        @RequestParam String password,
                        HttpSession session,
                        Model model) {
        AppUser appUser = appUserRepository.findByUsername(username);
        if (appUser == null || !appUser.getPassword().equals(password)) {
            model.addAttribute("error", "用户名或密码错误");
            return "login";
        }

        session.setAttribute("appUser", appUser);
        List<EmailAccount> accounts = emailAccountRepository.findByAppUserId(appUser.getId());

        if (!accounts.isEmpty()) {
            activateEmailAccount(session, accounts.get(0));
            return "redirect:/inbox";
        } else {
            return "redirect:/settings";
        }
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }

    private void activateEmailAccount(HttpSession session, EmailAccount account) {
        String realAuthCode = AESUtil.decrypt(account.getPassword());
        UserAccount userAccount = new UserAccount(account.getEmail(), realAuthCode, account.getType());
        session.setAttribute("currentUser", userAccount); 
        session.setAttribute("currentEmailId", account.getId()); 
    }


    // ================== 2. 邮件列表视图 ==================
    @GetMapping("/inbox")
    public ModelAndView inbox(HttpSession session,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "date") String sort,
                              @RequestParam(defaultValue = "desc") String order,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(defaultValue = "all") String searchType,
                              @RequestParam(required = false) String folder) { 

        if (session.getAttribute("appUser") == null) return new ModelAndView("redirect:/");
        if (session.getAttribute("currentUser") == null) return new ModelAndView("redirect:/settings");

        String targetFolder = StringUtils.hasText(folder) ? folder : "收件箱";
        return getFolderView(session, targetFolder, "/inbox", page, sort, order, keyword, searchType);
    }

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

    private ModelAndView getFolderView(HttpSession session, String folderName, String baseUrl,
                                       int page, String sort, String order, String keyword, String searchType) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) return new ModelAndView("redirect:/settings");

        ModelAndView mav = new ModelAndView("inbox");
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
        mav.addObject("contacts", contactRepository.findAll());

        return mav;
    }


    // ================== 3. 邮件操作 ==================

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
    
    @GetMapping("/preview")
    public ResponseEntity<Resource> previewFile(@RequestParam String filename) {
        try {
            if (filename.contains("..")) return ResponseEntity.badRequest().build();
            Path path = Paths.get(MailService.SAVE_PATH + filename);
            Resource resource = new UrlResource(path.toUri());
            if (resource.exists() || resource.isReadable()) {
                String contentType = Files.probeContentType(path);
                if (contentType == null) contentType = "application/octet-stream";
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

    @GetMapping("/sendPage")
    public ModelAndView sendPage(HttpSession session,
                                 @RequestParam(required = false) Long draftId,
                                 @RequestParam(required = false) Long replyUid,
                                 @RequestParam(required = false) String folder) {

        UserAccount user = (UserAccount) session.getAttribute("currentUser");
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

        draftRepository.save(new com.example.demo.entity.DraftEmail(user.getEmail(), to, subject, text));
        return "redirect:/drafts";
    }

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

    // ================== 5. 设置页与账号管理 ==================

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

        AppUser currentUser = appUserRepository.findById(appUser.getId()).orElse(null);
        if (currentUser == null) return "redirect:/";

        if (!currentUser.getPassword().equals(oldPassword)) {
            attributes.addFlashAttribute("error", "旧密码错误，请重试");
            return "redirect:/settings";
        }
        if (!newPassword.equals(confirmPassword)) {
            attributes.addFlashAttribute("error", "两次输入的新密码不一致");
            return "redirect:/settings";
        }

        currentUser.setPassword(newPassword);
        appUserRepository.save(currentUser);
        session.setAttribute("appUser", currentUser);

        attributes.addFlashAttribute("success", "密码修改成功！下次登录请使用新密码。");
        return "redirect:/settings";
    }

    // 【新增】修改头像接口
    @PostMapping("/settings/avatar")
    public String updateAvatar(@RequestParam("avatarFile") MultipartFile file, 
                               HttpSession session, 
                               RedirectAttributes attributes) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        if (file.isEmpty()) {
            attributes.addFlashAttribute("error", "请选择要上传的图片");
            return "redirect:/settings";
        }

        try {
            // 1. 确定保存路径 (保存到 src/main/resources/static/images/avatar/ 下)
            // 注意：开发环境下保存到 src 目录可以永久保存，但需要 IDE 重新编译才能立即访问
            // 为了演示方便，我们这里计算一下 project root
            String projectPath = System.getProperty("user.dir");
            String uploadDir = projectPath + "/src/main/resources/static/images/avatar/";
            
            // 另外，为了让当前运行环境(target)也能立即访问，建议也保存一份到 target
            String targetUploadDir = projectPath + "/target/classes/static/images/avatar/";

            File dir = new File(uploadDir);
            if (!dir.exists()) dir.mkdirs();
            File targetDir = new File(targetUploadDir);
            if (!targetDir.exists()) targetDir.mkdirs();

            // 2. 生成新文件名
            String originalFilename = file.getOriginalFilename();
            String suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
            String newFilename = UUID.randomUUID().toString() + suffix;

            // 3. 保存文件 (两处都存，确保开发环境立即生效且不丢失)
            file.transferTo(new File(targetDir, newFilename)); // 存到 target 供立即访问
            Files.copy(new File(targetDir, newFilename).toPath(), new File(uploadDir, newFilename).toPath()); // 复制到 src 供持久化

            // 4. 更新数据库
            AppUser currentUser = appUserRepository.findById(appUser.getId()).orElse(null);
            if (currentUser != null) {
                String avatarUrl = "/images/avatar/" + newFilename;
                currentUser.setAvatar(avatarUrl);
                appUserRepository.save(currentUser);
                
                // 更新 Session
                session.setAttribute("appUser", currentUser);
                attributes.addFlashAttribute("success", "头像修改成功！");
            }

        } catch (IOException e) {
            e.printStackTrace();
            attributes.addFlashAttribute("error", "头像上传失败：" + e.getMessage());
        }

        return "redirect:/settings";
    }

    @PostMapping("/account/add")
    public String addAccount(@RequestParam String email,
                             @RequestParam String password,
                             @RequestParam String type,
                             @RequestParam(required = false) String alias,
                             HttpSession session,
                             Model model) {

        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        String encryptedPwd = AESUtil.encrypt(password);
        EmailAccount newAccount = new EmailAccount(email, encryptedPwd, type, appUser.getId());
        emailAccountRepository.save(newAccount);

        if (session.getAttribute("currentUser") == null) {
            activateEmailAccount(session, newAccount);
        }

        return "redirect:/settings";
    }

    @GetMapping("/account/delete")
    public String deleteAccount(@RequestParam Long id, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        emailAccountRepository.findById(id).ifPresent(account -> {
            if (account.getAppUserId().equals(appUser.getId())) {
                emailAccountRepository.delete(account);

                Long currentId = (Long) session.getAttribute("currentEmailId");
                if (currentId != null && currentId.equals(id)) {
                    session.removeAttribute("currentUser");
                    session.removeAttribute("currentEmailId");

                    List<EmailAccount> others = emailAccountRepository.findByAppUserId(appUser.getId());
                    if (!others.isEmpty()) {
                        activateEmailAccount(session, others.get(0));
                    }
                }
            }
        });

        return "redirect:/settings";
    }

    @ModelAttribute
    public void addGlobalAttributes(Model model, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser != null) {
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

            UserAccount currentUser = (UserAccount) session.getAttribute("currentUser");
            if (currentUser != null) {
                List<String> folders = mailService.getCustomFolders(currentUser);
                model.addAttribute("customFolders", folders);
            }
        }
    }

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
}