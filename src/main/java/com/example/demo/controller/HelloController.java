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
import java.nio.file.Files; // 【修改点1：新增导入】
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

    // ================== 【修改点2：新增预览接口】 ==================
    @GetMapping("/preview")
    public ResponseEntity<Resource> previewFile(@RequestParam String filename) {
        try {
            // 安全检查：防止路径遍历
            if (filename.contains("..")) {
                return ResponseEntity.badRequest().build();
            }

            Path path = Paths.get(MailService.SAVE_PATH + filename);
            Resource resource = new UrlResource(path.toUri());

            if (resource.exists() || resource.isReadable()) {
                // 自动探测文件类型 (如 image/jpeg, application/pdf)
                String contentType = Files.probeContentType(path);
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }

                return ResponseEntity.ok()
                        // inline 告诉浏览器直接显示，而不是下载
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
    // =========================================================

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
        if (appUser == null) return "redirect:/";

        List<EmailAccount> accounts = emailAccountRepository.findByAppUserId(appUser.getId());
        model.addAttribute("accounts", accounts);
        model.addAttribute("currentFolder", "设置");
        return "settings";
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