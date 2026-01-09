package com.example.demo;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.awt.Desktop;
import java.net.URI;
import java.util.Optional;

public class DesktopLauncher extends Application {

    private ConfigurableApplicationContext springContext;

    @Override
    public void init() {
        // 1. 开启 headless(false) 以支持 AWT 调用 (队友的修改)
        springContext = new SpringApplicationBuilder(DemoApplication.class)
                .headless(false)
                .run();
    }

    @Override
    public void start(Stage primaryStage) {
        WebView webView = new WebView();
        WebEngine webEngine = webView.getEngine();

        // 2. 防止 JavaFX 在最后一个窗口还没关闭时就自动退出
        Platform.setImplicitExit(false);

        // ============================================================
        // 【核心修复 A】恢复弹窗处理功能 (解决删除垃圾箱没反应的问题)
        // ============================================================

        // 1. 处理 JavaScript 的 alert() 弹窗
        webEngine.setOnAlert(event -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText(event.getData());
            alert.showAndWait();
        });

        // 2. 处理 JavaScript 的 confirm() 确认框
        webEngine.setConfirmHandler(message -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("确认操作");
            alert.setHeaderText(null);
            alert.setContentText(message);

            Optional<ButtonType> result = alert.showAndWait();
            // 如果用户点击了“确定/OK”，返回 true 给 JS，否则返回 false
            return result.isPresent() && result.get() == ButtonType.OK;
        });

        // ============================================================
        // 【核心修复 B】保留队友的下载链接处理 (防止下载白屏)
        // ============================================================

        // 监听 URL 变化
        webEngine.locationProperty().addListener((observable, oldUrl, newUrl) -> {
            if (newUrl != null && !newUrl.isEmpty()) {
                String lowUrl = newUrl.toLowerCase();

                // 判断是不是下载链接
                if (lowUrl.contains("/download") ||
                        lowUrl.endsWith(".zip") || lowUrl.endsWith(".rar") ||
                        lowUrl.endsWith(".doc") || lowUrl.endsWith(".docx") ||
                        lowUrl.endsWith(".xls") || lowUrl.endsWith(".xlsx") ||
                        lowUrl.endsWith(".pdf") || lowUrl.endsWith(".txt")) {

                    // A. 马上让 WebView 停止跳转并回退，防止白屏
                    Platform.runLater(() -> {
                        if (oldUrl != null) {
                            webEngine.load(oldUrl);
                        }
                    });

                    // B. 启动一个新线程去调用系统浏览器
                    new Thread(() -> {
                        try {
                            Desktop.getDesktop().browse(new URI(newUrl));
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.err.println("❌ 无法调用系统浏览器: " + e.getMessage());
                        }
                    }).start();
                }
            }
        });

        // 加载首页
        webEngine.load("http://localhost:8080/");

        Scene scene = new Scene(webView, 1280, 800);
        primaryStage.setScene(scene);
        primaryStage.setTitle("电子邮件管理系统");

        // 当点击右上角 X 关闭窗口时，彻底退出程序
        primaryStage.setOnCloseRequest(event -> {
            stop();
        });

        primaryStage.show();
    }

    @Override
    public void stop() {
        // 确保彻底关闭 Spring Boot 和所有线程
        if (springContext != null) {
            springContext.close();
        }
        Platform.exit();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}