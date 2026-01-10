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

/**
 * 通讯录控制器：处理联系人的增删改查及 CSV 导入导出
 */
@Controller
public class ContactController {

    @Autowired
    private ContactRepository contactRepository; // 注入联系人数据库操作接口

    @Autowired
    private EmailAccountRepository emailAccountRepository; // 注入邮件账户数据库操作接口

    /**
     * 访问通讯录列表页面
     * @param session 用于获取当前登录用户信息
     */
    @GetMapping("/contacts")
    public ModelAndView list(HttpSession session) {
        // 1. 身份校验：若未登录则重定向至首页
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) {
            return new ModelAndView("redirect:/");
        }

        // 指定返回的视图模板名称为 contacts.html
        ModelAndView mav = new ModelAndView("contacts");

        // 2. 核心数据：查询当前用户名下的所有联系人
        List<Contact> contacts = contactRepository.findByAppUserId(appUser.getId());
        mav.addObject("contacts", contacts);
        mav.addObject("appUser", appUser);

        // 3. UI 辅助数据：查询用户绑定的邮箱账号（用于侧边栏展示）
        List<EmailAccount> myAccounts = emailAccountRepository.findByAppUserId(appUser.getId());
        mav.addObject("myAccounts", myAccounts);

        // 4. 状态保持：记录当前操作的邮箱 ID 和 文件夹名称
        Long currentId = (Long) session.getAttribute("currentEmailId");
        mav.addObject("currentEmailId", currentId);
        mav.addObject("currentFolder", "通讯录");

        return mav;
    }

    /**
     * 新增联系人
     */
    @PostMapping("/contact/add")
    public String add(@RequestParam String name, @RequestParam String email, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        // 创建新联系人实体并保存
        contactRepository.save(new Contact(name, email, appUser.getId()));
        return "redirect:/contacts";
    }

    /**
     * 更新/编辑联系人信息
     */
    @PostMapping("/contact/update")
    public String update(@RequestParam Long id, @RequestParam String name, @RequestParam String email, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        // 1. 根据 ID 查询联系人
        contactRepository.findById(id).ifPresent(contact -> {
            // 2. 安全检查：确保只能修改属于当前用户自己的联系人
            if (contact.getAppUserId().equals(appUser.getId())) {
                contact.setName(name);
                contact.setEmail(email);
                contactRepository.save(contact); // 执行更新
            }
        });
        return "redirect:/contacts";
    }

    /**
     * 删除联系人
     */
    @GetMapping("/contact/delete")
    public String delete(@RequestParam Long id) {
        // 实际应用中建议此处也加入用户 ID 校验
        contactRepository.deleteById(id);
        return "redirect:/contacts";
    }

    // ================== CSV 导出功能 ==================

    /**
     * 导出通讯录为 CSV 文件
     */
    @GetMapping("/contacts/export")
    public void exportCsv(HttpServletResponse response, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return;

        try {
            // 1. 设置 HTTP 响应头：定义内容类型为 CSV，设置文件名
            response.setContentType("text/csv; charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"contacts.csv\"");

            // 2. 从数据库获取该用户的所有联系人
            List<Contact> contacts = contactRepository.findByAppUserId(appUser.getId());

            // 3. 使用 PrintWriter 写入响应流
            PrintWriter writer = response.getWriter();

            // [关键] 写入 UTF-8 BOM (\ufeff)，确保 Excel 打开时不会出现中文乱码
            writer.write('\ufeff');

            // 4. 写入 CSV 表头
            writer.println("姓名,邮箱");

            // 5. 遍历联系人数据并手动构建每一行
            for (Contact c : contacts) {
                // 处理 CSV 转义：将内容中的双引号替换为两个双引号，防止格式崩溃
                String name = c.getName() != null ? c.getName().replace("\"", "\"\"") : "";
                String email = c.getEmail() != null ? c.getEmail().replace("\"", "\"\"") : "";

                // 标准做法是用双引号包裹字段，用逗号分隔
                writer.println("\"" + name + "\",\"" + email + "\"");
            }

            // 6. 刷新并关闭流
            writer.flush();
            writer.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ================== CSV 导入功能 ==================

    /**
     * 从上传的 CSV 文件导入联系人
     */
    @PostMapping("/contacts/import")
    public String importCsv(@RequestParam("file") MultipartFile file, HttpSession session) {
        AppUser appUser = (AppUser) session.getAttribute("appUser");
        if (appUser == null) return "redirect:/";

        if (file.isEmpty()) return "redirect:/contacts";

        try {
            // 1. 读取文件内容为字符串
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);

            // 2. 移除可能存在的 BOM 头，避免解析首行时出错
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }

            // 3. 使用 Apache Commons CSV 解析内容
            try (java.io.Reader reader = new java.io.StringReader(content)) {
                // 设置：首行为表头、忽略大小写、去除空格
                CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT
                        .withFirstRecordAsHeader()
                        .withIgnoreHeaderCase()
                        .withTrim());

                List<Contact> newContacts = new ArrayList<>();
                for (CSVRecord record : parser) {
                    // 兼容多种可能的表头名称（支持中英文）
                    String name = getColumnValue(record, "姓名", "name", "Name");
                    String email = getColumnValue(record, "邮箱", "email", "Email", "邮箱地址");

                    // 4. 只有邮箱不为空时才加入导入列表
                    if (name != null && email != null && !email.trim().isEmpty()) {
                        newContacts.add(new Contact(name, email, appUser.getId()));
                    }
                }

                // 5. 批量保存到数据库
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
     * 辅助方法：在一个 CSV 行记录中尝试获取多个可能键名对应的值
     */
    private String getColumnValue(CSVRecord record, String... keys) {
        for (String key : keys) {
            if (record.isMapped(key)) {
                return record.get(key);
            }
        }
        return null;
    }
}