package com.example.demo.entity;

import jakarta.persistence.*;

@Entity
public class TrashEmail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sender;
    private String title;
    private String sendTime;

    public TrashEmail() {}

    public TrashEmail(String sender, String title, String sendTime) {
        this.sender = sender;
        this.title = title;
        this.sendTime = sendTime;
    }

    public Long getId() { return id; }
    public String getSender() { return sender; }
    public String getTitle() { return title; }
    public String getSendTime() { return sendTime; }
}