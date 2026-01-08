package com.example.demo.controller;

import com.example.demo.entity.SentLog;
import com.example.demo.entity.TrashEmail;
import com.example.demo.model.EmailInfo;
import com.example.demo.model.UserAccount;
import com.example.demo.repository.ContactRepository;
import com.example.demo.repository.SentLogRepository;
import com.example.demo.repository.TrashEmailRepository;
import com.example.demo.service.MailService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model; // 【新增】引入 Model
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Controller
public class HelloController {

    @Autowired
    private MailService mailService;
    @Autowired
    private ContactRepository contactRepository;
    @Autowired
    private SentLogRepository sentLogRepository;
    @Autowired
    private TrashEmailRepository trashRepository;

    @GetMapping("/")
    public String root() { return "login"; }

    @PostMapping("/login")
    public String login(@RequestParam String email, @RequestParam String password, @RequestParam String type, HttpSession session) {
        UserAccount user = new UserAccount(email, password, type);
        session.setAttribute("currentUser", user);
        return "redirect:/inbox";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.removeAttribute("currentUser");
        return "redirect:/";
    }

    @GetMapping("/inbox")
    public ModelAndView inbox(HttpSession session,
            @RequestParam(defaultValue = "1") int page) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null)
            return new ModelAndView("redirect:/");

        ModelAndView mav = new ModelAndView("inbox");

        int pageSize = 10;
        java.util.Map<String, Object> result = mailService.receiveEmails(user, page, pageSize);
        List<EmailInfo> emails = (List<EmailInfo>) result.get("list");
        int totalCount = (int) result.get("totalCount");
        int totalPages = (int) Math.ceil((double) totalCount / pageSize);

        mav.addObject("emails", emails);
        mav.addObject("currentFolder", "收件箱");
        mav.addObject("currentPage", page);
        mav.addObject("totalPages", totalPages);
        mav.addObject("totalCount", totalCount);

        return mav;
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

    @GetMapping("/sent")
    public ModelAndView sentBox(HttpSession session) {
        if (session.getAttribute("currentUser") == null) return new ModelAndView("redirect:/");

        ModelAndView mav = new ModelAndView("inbox");
        List<SentLog> sentLogs = sentLogRepository.findAll();
        List<EmailInfo> emails = new ArrayList<>();

        for (SentLog log : sentLogs) {
            emails.add(new EmailInfo(
                    log.getId(),
                    log.getTitle(),
                    "收件人: " + log.getReceiver(),
                    log.getSendTime(),
                    log.getContent(),
                    new ArrayList<>() 
            ));
        }

        mav.addObject("emails", emails);
        mav.addObject("currentFolder", "已发送");
        return mav;
    }

    @GetMapping("/trash")
    public ModelAndView trashBox(HttpSession session) {
        if (session.getAttribute("currentUser") == null) return new ModelAndView("redirect:/");

        ModelAndView mav = new ModelAndView("inbox");
        List<TrashEmail> trashList = trashRepository.findAll();
        List<EmailInfo> emails = new ArrayList<>();

        for (TrashEmail t : trashList) {
            emails.add(new EmailInfo(
                    t.getId(),
                    t.getTitle(),
                    t.getSender(),
                    t.getSendTime(),
                    "", 
                    new ArrayList<>()
            ));
        }

        mav.addObject("emails", emails);
        mav.addObject("currentFolder", "垃圾箱");
        return mav;
    }

    @GetMapping("/sendPage")
    public ModelAndView sendPage(HttpSession session) {
        if (session.getAttribute("currentUser") == null) return new ModelAndView("redirect:/");
        ModelAndView mav = new ModelAndView("send");
        mav.addObject("contacts", contactRepository.findAll());
        return mav;
    }

    @PostMapping("/sendMail")
    public ModelAndView sendMail(@RequestParam String to, @RequestParam String text,
                                 @RequestParam(value = "file", required = false) MultipartFile file,
                                 HttpSession session) {
        UserAccount user = (UserAccount) session.getAttribute("currentUser");
        if (user == null) return new ModelAndView("redirect:/");

        String subject = "桌面软件邮件";
        mailService.sendMailWithAttachment(user, to, subject, text, file);
        sentLogRepository.save(new SentLog(to, subject, text));
        return new ModelAndView("redirect:/inbox");
    }

    @GetMapping("/deleteFromSent")
    public String deleteFromSent(@RequestParam Long id) {
        SentLog log = sentLogRepository.findById(id).orElse(null);
        if (log != null) {
            trashRepository.save(new TrashEmail(
                    "收件人: " + log.getReceiver(),
                    log.getTitle(),
                    log.getSendTime()
            ));
            sentLogRepository.delete(log);
        }
        return "redirect:/sent";
    }

    @GetMapping("/deleteForever")
    public String deleteForever(@RequestParam Long id) {
        trashRepository.deleteById(id);
        return "redirect:/trash";
    }

    // ============ 新增：设置页面的路由 ============
    @GetMapping("/settings")
    public String settingsPage(HttpSession session, Model model) {
        // 1. 检查是否登录
        if (session.getAttribute("currentUser") == null) {
            return "redirect:/";
        }
        
        // 2. 告诉前端当前选中的是“设置” (用于侧边栏高亮)
        model.addAttribute("currentFolder", "设置");
        
        // 3. 跳转到 settings.html
        return "settings"; 
    }
}