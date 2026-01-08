package com.example.demo.controller;

import com.example.demo.entity.Contact;
import com.example.demo.repository.ContactRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;

@Controller
public class ContactController {

    @Autowired
    private ContactRepository contactRepository;

    @GetMapping("/contacts")
    public ModelAndView list(HttpSession session) {
        if (session.getAttribute("currentUser") == null) {
            return new ModelAndView("redirect:/");
        }

        ModelAndView mav = new ModelAndView("contacts");
        List<Contact> contacts = contactRepository.findAll();
        mav.addObject("contacts", contacts);
        return mav;
    }

    @PostMapping("/contact/add")
    public String add(@RequestParam String name, @RequestParam String email) {
        contactRepository.save(new Contact(name, email));
        return "redirect:/contacts";
    }

    @GetMapping("/contact/delete")
    public String delete(@RequestParam Long id) {
        contactRepository.deleteById(id);
        return "redirect:/contacts";
    }
}
