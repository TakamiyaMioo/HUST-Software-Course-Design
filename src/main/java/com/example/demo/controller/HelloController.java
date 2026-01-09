package com.example.demo.controller;

import com.example.demo.entity.SentLog;
import com.example.demo.model.EmailInfo;
import com.example.demo.model.UserAccount;
import com.example.demo.repository.ContactRepository;
import com.example.demo.repository.DraftRepository;
import com.example.demo.repository.SentLogRepository;
import com.example.demo.service.MailService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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

    @GetMapping("/")
    public String root() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String email, @RequestParam String password, @RequestParam String type,
            HttpSession session) {
        UserAccount user = new UserAccount(email, password, type);
        session.setAttribute("currentUser", user);
        return "redirect:/inbox";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.removeAttribute("currentUser");
        return "redirect:/";
    }

    // --- 核心邮件列表视图 (修改：增加 searchType) ---

    // 1. 收件箱
    @GetMapping("/inbox")
    public ModelAndView inbox(HttpSession session,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "all") String searchType) {
        return getFolderView(session, "收件箱", "/inbox", page, sort, order, keyword, searchType);
    }

    // 2. 已发送
    @GetMapping("/sent")
    public ModelAndView sentBox(HttpSession session,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "all") String searchType) {
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
        return getFolderView(session, "已删除", "/trash", page, sort, order, keyword, searchType);
    }

    /**
     * 公共方法：构建文件夹视图
     */
    private ModelAndView getFolderView(HttpSession session, String folderName, String baseUrl,
            int page, String sort, String order, String keyword, String searchType) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null)
            return new ModelAndView("redirect:/");

        ModelAndView mav = new ModelAndView("inbox");

        // 1. 传递 keyword 和 searchType 到 Service
        Map<String, Object> result = mailService.receiveEmails(user, folderName, page, 10, sort, order, keyword,
                searchType);

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
        mav.addObject("searchType", searchType); // 回传搜索类型

        mav.addObject("contacts", contactRepository.findAll());

        return mav;
    }

    // --- 邮件详情 ---
    @GetMapping("/email/detail")
    @ResponseBody
    public Map<String, Object> getEmailDetail(@RequestParam String folder, @RequestParam Long uid,
            HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        Map<String, Object> resp = new HashMap<>();
        if (user == null) {
            resp.put("error", "未登录");
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

    // 修改 HelloController.java 中的 sendPage 方法
    @GetMapping("/sendPage")
    public ModelAndView sendPage(HttpSession session,
            @RequestParam(required = false) Long draftId,
            @RequestParam(required = false) Long replyUid,
            @RequestParam(required = false) String folder) {

        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null)
            return new ModelAndView("redirect:/");

        ModelAndView mav = new ModelAndView("send");
        mav.addObject("contacts", contactRepository.findAll());
        mav.addObject("currentFolder", "写信");

        // 1. 如果是草稿，还是照旧（因为草稿是简单的文本，通常没问题，或者你也可以改成前端加载）
        if (draftId != null) {
            draftRepository.findById(draftId).ifPresent(draft -> {
                mav.addObject("draftReceiver", draft.getReceiver());
                mav.addObject("draftTitle", draft.getTitle());
                mav.addObject("draftContent", draft.getContent());
            });
            return mav;
        }

        // 2. 【修改重点】如果是回复，我们不再去查邮件内容，只把 ID 和 Folder 传给前端页面
        if (replyUid != null && folder != null) {
            // 先去查一下简单的头信息填好收件人和标题（这步很快，不容易出错）
            EmailInfo originalEmail = mailService.getEmailDetail(user, folder, replyUid);
            if (originalEmail != null) {
                mav.addObject("draftReceiver", originalEmail.getAddress());
                mav.addObject("draftTitle", "Re: " + originalEmail.getTitle());

                // 关键：把 ID 和 Folder 传给前端，让前端 JS 去加载正文
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
        if (user == null)
            return new ModelAndView("redirect:/");

        // 回复场景：把原邮件的 uid/folder 传给 Service，让后端自动附带原附件（前端无法自动写入 file input）
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
            resp.put("error", "登录已过期");
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

    @PostMapping("/saveDraft")
    public String saveDraft(@RequestParam(required = false) String to,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String text,
            HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null)
            return "redirect:/";
        draftRepository.save(new com.example.demo.entity.DraftEmail(user.getEmail(), to, subject, text));
        return "redirect:/drafts";
    }

    @GetMapping("/drafts")
    public ModelAndView draftBox(HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null)
            return new ModelAndView("redirect:/");
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
        // 调用 mailService 的通用删除方法，文件夹传入 "收件箱"
        if (user != null)
            mailService.moveToTrash(user, "收件箱", id);
        return "redirect:/inbox"; // 操作完成后刷新收件箱
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

    @GetMapping("/settings")
    public String settingsPage(HttpSession session, Model model) {
        if (session.getAttribute("currentUser") == null)
            return "redirect:/";
        model.addAttribute("currentFolder", "设置");
        return "settings";
    }

}