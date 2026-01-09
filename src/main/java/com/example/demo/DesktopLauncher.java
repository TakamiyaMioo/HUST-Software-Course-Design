package com.example.demo;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.awt.Desktop;
import java.net.URI;

public class DesktopLauncher extends Application {

    private ConfigurableApplicationContext springContext;

    @Override
    public void init() {
        // ==================== 【修改点】 ====================
        // .headless(false) 告诉 Spring Boot 我们不是在跑服务器，是在跑桌面软件
        // 这样 java.awt.Desktop 才能正常工作
        springContext = new SpringApplicationBuilder(DemoApplication.class)
                .headless(false)
                .run();
        // ===================================================
    }

    @Override
    public void start(Stage primaryStage) {
        WebView webView = new WebView();
        WebEngine webEngine = webView.getEngine();

        // 监听 URL 变化，接管下载
        webEngine.locationProperty().addListener((observable, oldUrl, newUrl) -> {
            if (newUrl != null && !newUrl.isEmpty()) {
                String lowUrl = newUrl.toLowerCase();
                if (lowUrl.contains("/download") ||
                        lowUrl.endsWith(".zip") || lowUrl.endsWith(".rar") ||
                        lowUrl.endsWith(".doc") || lowUrl.endsWith(".docx") ||
                        lowUrl.endsWith(".xls") || lowUrl.endsWith(".xlsx") ||
                        lowUrl.endsWith(".pdf") || lowUrl.endsWith(".txt")) {

                    Platform.runLater(() -> {
                        if (oldUrl != null) {
                            webEngine.load(oldUrl);
                        }
                    });

                    try {
                        // 现在加上了 headless(false)，这就不会报错了
                        Desktop.getDesktop().browse(new URI(newUrl));
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.err.println("❌ 无法调用系统浏览器下载: " + e.getMessage());
                    }
                }
            }
        });

        webEngine.load("http://localhost:8080/");

        Scene scene = new Scene(webView, 1280, 800);
        primaryStage.setScene(scene);
        primaryStage.setTitle("电子邮件管理系统");
        primaryStage.show();
    }

    @Override
    public void stop() {
        springContext.close();
        Platform.exit();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}