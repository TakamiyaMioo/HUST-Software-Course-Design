package com.example.demo.controller;

import com.example.demo.entity.AppUser;
import com.example.demo.entity.Contact;
import com.example.demo.entity.EmailAccount;
import com.example.demo.repository.ContactRepository;
import com.example.demo.repository.EmailAccountRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Controller
public class ContactController {

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private EmailAccountRepository emailAccountRepository;

    // 通讯录列表页
    @GetMapping("/contacts")
    public ModelAndView list(HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) {
            return new ModelAndView("redirect:/");
        }

        ModelAndView mav = new ModelAndView("contacts");

        // 1. 查联系人
        List<Contact> contacts = contactRepository.findByAppUserId(appUser.getId());
        mav.addObject("contacts", contacts);
        mav.addObject("appUser", appUser);

        // 2. 补全侧边栏所需数据
        List<EmailAccount> myAccounts = emailAccountRepository.findByAppUserId(appUser.getId());
        mav.addObject("myAccounts", myAccounts);

        Long currentId = (Long) session.getAttribute("currentEmailId");
        mav.addObject("currentEmailId", currentId);
        mav.addObject("currentFolder", "通讯录");

        return mav;
    }

    @PostMapping("/contact/add")
    public String add(@RequestParam String name, @RequestParam String email, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";
        contactRepository.save(new Contact(name, email, appUser.getId()));
        return "redirect:/contacts";
    }

    // 更新联系人接口
    @PostMapping("/contact/update")
    public String update(@RequestParam Long id, @RequestParam String name, @RequestParam String email, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        // 查出该联系人，并确保它属于当前登录用户（安全检查）
        contactRepository.findById(id).ifPresent(contact -> {
            if (contact.getAppUserId().equals(appUser.getId())) {
                contact.setName(name);
                contact.setEmail(email);
                contactRepository.save(contact);
            }
        });
        return "redirect:/contacts";
    }

    @GetMapping("/contact/delete")
    public String delete(@RequestParam Long id) {
        contactRepository.deleteById(id);
        return "redirect:/contacts";
    }

    // ================== CSV 导出功能 (核心修复) ==================

    @GetMapping("/contacts/export")
    public void exportCsv(HttpServletResponse response, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return;

        try {
            // 1. 关键：设置响应头，告诉浏览器这是文件下载
            response.setContentType("text/csv; charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"contacts.csv\"");

            // 2. 获取数据
            List<Contact> contacts = contactRepository.findByAppUserId(appUser.getId());

            // 3. 使用 PrintWriter 直接写入，确保数据刷入响应流
            PrintWriter writer = response.getWriter();

            // 写入 BOM 防止 Excel 打开乱码
            writer.write('\ufeff');

            // 写入表头
            writer.println("姓名,邮箱");

            // 4. 遍历并写入每一行 (手动处理 CSV 格式比库更轻量且可控)
            for (Contact c : contacts) {
                // 处理可能存在的英文逗号和引号，防止 CSV 格式错乱
                String name = c.getName() != null ? c.getName().replace("\"", "\"\"") : "";
                String email = c.getEmail() != null ? c.getEmail().replace("\"", "\"\"") : "";

                // CSV 标准格式："值","值"
                writer.println("\"" + name + "\",\"" + email + "\"");
            }

            // 5. 强制刷新缓冲区，确保浏览器收到数据
            writer.flush();
            writer.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ================== CSV 导入功能 (保持不变) ==================

    @PostMapping("/contacts/import")
    public String importCsv(@RequestParam("file") MultipartFile file, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        if (file.isEmpty()) return "redirect:/contacts";

        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }

            try (java.io.Reader reader = new java.io.StringReader(content)) {
                CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim());
                List<Contact> newContacts = new ArrayList<>();
                for (CSVRecord record : parser) {
                    String name = getColumnValue(record, "姓名", "name", "Name");
                    String email = getColumnValue(record, "邮箱", "email", "Email", "邮箱地址");

                    if (name != null && email != null && !email.trim().isEmpty()) {
                        newContacts.add(new Contact(name, email, appUser.getId()));
                    }
                }
                if (!newContacts.isEmpty()) {
                    contactRepository.saveAll(newContacts);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "redirect:/contacts";
    }

    private String getColumnValue(CSVRecord record, String... keys) {
        for (String key : keys) {
            if (record.isMapped(key)) {
                return record.get(key);
            }
        }
        return null;
    }
}
