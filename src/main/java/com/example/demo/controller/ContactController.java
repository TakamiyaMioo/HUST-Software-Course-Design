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
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Controller
public class ContactController {

    @Autowired
    private ContactRepository contactRepository;

    // 【新增注入】为了修复侧边栏，我们需要查邮箱表
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

        // 1.原本的逻辑：查联系人
        List<Contact> contacts = contactRepository.findByAppUserId(appUser.getId());
        mav.addObject("contacts", contacts);
        mav.addObject("appUser", appUser);

        // ================== 【修复核心】补全侧边栏所需数据 ==================
        // 2. 查出该主账号下的所有邮箱 (用于侧边栏列表)
        List<EmailAccount> myAccounts = emailAccountRepository.findByAppUserId(appUser.getId());
        mav.addObject("myAccounts", myAccounts);

        // 3. 传当前的活动邮箱ID (用于侧边栏高亮)
        Long currentId = (Long) session.getAttribute("currentEmailId");
        mav.addObject("currentEmailId", currentId);

        // 4. 设置当前文件夹名称，让侧边栏的"通讯录"按钮高亮
        mav.addObject("currentFolder", "通讯录");
        // ================================================================

        return mav;
    }

    // --- 下面的代码保持不变 ---

    @PostMapping("/contact/add")
    public String add(@RequestParam String name, @RequestParam String email, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";
        contactRepository.save(new Contact(name, email, appUser.getId()));
        return "redirect:/contacts";
    }

    @GetMapping("/contact/delete")
    public String delete(@RequestParam Long id) {
        contactRepository.deleteById(id);
        return "redirect:/contacts";
    }

    // ================== CSV 导入导出功能 (保持不变) ==================

    @GetMapping("/contacts/export")
    public void exportCsv(HttpServletResponse response, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return;

        try {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"contacts.csv\"");

            List<Contact> contacts = contactRepository.findByAppUserId(appUser.getId());

            OutputStreamWriter writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
            writer.write('\ufeff');

            CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("姓名", "邮箱"));
            for (Contact c : contacts) {
                printer.printRecord(c.getName(), c.getEmail());
            }
            printer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @PostMapping("/contacts/import")
    public String importCsv(@RequestParam("file") MultipartFile file, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        if (file.isEmpty()) return "redirect:/contacts";

        try {
            // 1. 【核心修改】先读取文件内容为 String，处理 BOM 问题
            // 假设用户保存的是 UTF-8 (这是推荐格式)
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);

            // 如果开头是 BOM 字符 (\uFEFF)，把它去掉
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }

            // 2. 用 StringReader 代替 InputStreamReader
            try (java.io.Reader reader = new java.io.StringReader(content)) {

                CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim());

                List<Contact> newContacts = new ArrayList<>();
                for (CSVRecord record : parser) {
                    // 3. 【增强逻辑】尝试多种表头名称 (中文/英文/带BOM残留的)
                    String name = getColumnValue(record, "姓名", "name", "Name");
                    String email = getColumnValue(record, "邮箱", "email", "Email", "邮箱地址");

                    // 4. 数据校验：确保邮箱不为空
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

    /**
     * 辅助方法：尝试从 CSV 行中获取数据（支持多个备选列名）
     */
    private String getColumnValue(CSVRecord record, String... keys) {
        for (String key : keys) {
            if (record.isMapped(key)) {
                return record.get(key);
            }
        }
        // 兜底：如果都没匹配上，打印一下当前有的 header 方便调试
        // System.out.println("Available headers: " + record.getParser().getHeaderMap().keySet());
        return null;
    }

//    @PostMapping("/contacts/import")
//    public String importCsv(@RequestParam("file") MultipartFile file, HttpSession session) {
//        AppUser appUser = (AppUser) session.getAttribute("appUser");
//        if (appUser == null) return "redirect:/";
//
//        if (file.isEmpty()) return "redirect:/contacts";
//
//        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
//            CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim());
//            List<Contact> newContacts = new ArrayList<>();
//            for (CSVRecord record : parser) {
//                String name = record.isMapped("姓名") ? record.get("姓名") : (record.isMapped("name") ? record.get("name") : null);
//                String email = record.isMapped("邮箱") ? record.get("邮箱") : (record.isMapped("email") ? record.get("email") : null);
//                if (name != null && email != null && !email.isEmpty()) {
//                    newContacts.add(new Contact(name, email, appUser.getId()));
//                }
//            }
//            if (!newContacts.isEmpty()) {
//                contactRepository.saveAll(newContacts);
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return "redirect:/contacts";
//    }
}


//package com.example.demo.controller;
//
//import com.example.demo.entity.AppUser;
//import com.example.demo.entity.Contact;
//import com.example.demo.repository.ContactRepository;
//import jakarta.servlet.http.HttpServletResponse;
//import jakarta.servlet.http.HttpSession;
//import org.apache.commons.csv.CSVFormat;
//import org.apache.commons.csv.CSVParser;
//import org.apache.commons.csv.CSVPrinter;
//import org.apache.commons.csv.CSVRecord;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Controller;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.multipart.MultipartFile;
//import org.springframework.web.servlet.ModelAndView;
//
//import java.io.BufferedReader;
//import java.io.InputStreamReader;
//import java.io.OutputStreamWriter;
//import java.nio.charset.StandardCharsets;
//import java.util.ArrayList;
//import java.util.List;
//
//@Controller
//public class ContactController {
//
//    @Autowired
//    private ContactRepository contactRepository;
//
//    // 通讯录列表页
//    @GetMapping("/contacts")
//    public ModelAndView list(HttpSession session) {
//        AppUser appUser = (AppUser) session.getAttribute("appUser");
//        if (appUser == null) {
//            return new ModelAndView("redirect:/");
//        }
//
//        ModelAndView mav = new ModelAndView("contacts");
//        // 修改：只查询当前登录用户的联系人
//        List<Contact> contacts = contactRepository.findByAppUserId(appUser.getId());
//        mav.addObject("contacts", contacts);
//
//        // 传递用户信息给侧边栏
//        mav.addObject("appUser", appUser);
//        return mav;
//    }
//
//    // 添加联系人
//    @PostMapping("/contact/add")
//    public String add(@RequestParam String name, @RequestParam String email, HttpSession session) {
//        AppUser appUser = (AppUser) session.getAttribute("appUser");
//        if (appUser == null) return "redirect:/";
//
//        // 保存时带上 appUserId
//        contactRepository.save(new Contact(name, email, appUser.getId()));
//        return "redirect:/contacts";
//    }
//
//    // 删除联系人
//    @GetMapping("/contact/delete")
//    public String delete(@RequestParam Long id) {
//        contactRepository.deleteById(id);
//        return "redirect:/contacts";
//    }
//
//    // ================== CSV 导入导出功能 ==================
//
//    /**
//     * 导出功能：生成 CSV 文件供用户下载
//     */
//    @GetMapping("/contacts/export")
//    public void exportCsv(HttpServletResponse response, HttpSession session) {
//        AppUser appUser = (AppUser) session.getAttribute("appUser");
//        if (appUser == null) return;
//
//        try {
//            // 1. 设置响应头，告诉浏览器这是一个要下载的文件
//            response.setContentType("text/csv; charset=UTF-8");
//            response.setHeader("Content-Disposition", "attachment; filename=\"contacts.csv\"");
//
//            // 2. 查出数据
//            List<Contact> contacts = contactRepository.findByAppUserId(appUser.getId());
//
//            // 3. 写入 CSV (处理中文乱码关键：使用 UTF-8 带 BOM 或者标准 UTF-8)
//            // 这里使用 OutputStreamWriter 包装 outputStream
//            OutputStreamWriter writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
//            // 写入 BOM 头，这让 Excel 能自动识别为 UTF-8，防止中文乱码
//            writer.write('\ufeff');
//
//            CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("姓名", "邮箱"));
//
//            for (Contact c : contacts) {
//                printer.printRecord(c.getName(), c.getEmail());
//            }
//
//            printer.flush();
//            writer.close();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//
//    /**
//     * 导入功能：接收上传的 CSV 并存入数据库
//     */
//    @PostMapping("/contacts/import")
//    public String importCsv(@RequestParam("file") MultipartFile file, HttpSession session) {
//        AppUser appUser = (AppUser) session.getAttribute("appUser");
//        if (appUser == null) return "redirect:/";
//
//        if (file.isEmpty()) return "redirect:/contacts";
//
//        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
//            // 解析 CSV，自动识别表头
//            CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim());
//
//            List<Contact> newContacts = new ArrayList<>();
//
//            for (CSVRecord record : parser) {
//                // 容错处理：尝试获取 name 或 姓名
//                String name = record.isMapped("姓名") ? record.get("姓名") : (record.isMapped("name") ? record.get("name") : null);
//                String email = record.isMapped("邮箱") ? record.get("邮箱") : (record.isMapped("email") ? record.get("email") : null);
//
//                // 如果这一行数据有效，则添加
//                if (name != null && email != null && !email.isEmpty()) {
//                    newContacts.add(new Contact(name, email, appUser.getId()));
//                }
//            }
//
//            if (!newContacts.isEmpty()) {
//                contactRepository.saveAll(newContacts);
//            }
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            // 实际项目中这里可以返回错误提示
//        }
//
//        return "redirect:/contacts";
//    }
//}
