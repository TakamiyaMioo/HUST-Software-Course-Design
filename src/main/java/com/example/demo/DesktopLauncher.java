package com.example.demo;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.awt.Desktop;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Optional;

public class DesktopLauncher extends Application {

    private ConfigurableApplicationContext springContext;

    @Override
    public void init() {
        springContext = new SpringApplicationBuilder(DemoApplication.class)
                .headless(false)
                .run();
    }

    @Override
    public void start(Stage primaryStage) {
        WebView webView = new WebView();
        WebEngine webEngine = webView.getEngine();
        Platform.setImplicitExit(false);

        // 1. 弹窗处理
        webEngine.setOnAlert(event -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText(event.getData());
            alert.showAndWait();
        });

        webEngine.setConfirmHandler(message -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("确认");
            alert.setHeaderText(null);
            alert.setContentText(message);
            Optional<ButtonType> result = alert.showAndWait();
            return result.isPresent() && result.get() == ButtonType.OK;
        });

        // 2. 核心监听器
        webEngine.locationProperty().addListener((observable, oldUrl, newUrl) -> {
            if (newUrl != null && !newUrl.isEmpty()) {
                String lowUrl = newUrl.toLowerCase();

                // 排除预览链接
                if (!lowUrl.contains("/preview")) {

                    // =======================================================
                    // 【情况 A】导出/CSV：内部下载 (修复线程崩溃问题)
                    // =======================================================
                    if (lowUrl.contains("export") || lowUrl.endsWith(".csv")) {

                        // 【关键修复】把所有逻辑包在 runLater 里，防止阻塞监听器导致 Crash
                        Platform.runLater(() -> {
                            // 1. 尝试获取 Cookie (加了异常保护)
                            String cookie = null;
                            try {
                                cookie = (String) webEngine.executeScript("document.cookie");
                            } catch (Exception e) {
                                System.err.println("获取Cookie失败(不影响下载尝试): " + e.getMessage());
                            }

                            // 2. 马上回退页面，防止白屏
                            if (oldUrl != null) webEngine.load(oldUrl);

                            // 3. 弹出保存框 (这时候弹就不崩了)
                            FileChooser fileChooser = new FileChooser();
                            fileChooser.setTitle("导出通讯录");
                            fileChooser.setInitialFileName("contacts.csv");
                            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 文件", "*.csv"));

                            File file = fileChooser.showSaveDialog(primaryStage);

                            // 4. 启动下载线程
                            if (file != null) {
                                String finalCookie = cookie;
                                new Thread(() -> downloadFileInternally(newUrl, file, finalCookie)).start();
                            }
                        });
                    }
                    // =======================================================
                    // 【情况 B】普通附件：调用系统浏览器
                    // =======================================================
                    else if (lowUrl.contains("/download") ||
                            lowUrl.endsWith(".zip") || lowUrl.endsWith(".rar") ||
                            lowUrl.endsWith(".doc") || lowUrl.endsWith(".docx") ||
                            lowUrl.endsWith(".xls") || lowUrl.endsWith(".xlsx") ||
                            lowUrl.endsWith(".pdf") || lowUrl.endsWith(".txt")) {

                        Platform.runLater(() -> {
                            if (oldUrl != null) webEngine.load(oldUrl);
                        });

                        new Thread(() -> {
                            try {
                                Desktop.getDesktop().browse(new URI(newUrl));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }).start();
                    }
                }
            }
        });

        // 1. 动态获取 Spring Boot 实际启动的端口号
        String port = springContext.getEnvironment().getProperty("local.server.port");

        // 2. 拼接成正确的 URL
        String url = "http://localhost:" + port + "/";

        // 3. 加载页面
        webEngine.load(url);
        System.out.println("正在访问动态端口: " + port); // (可选) 打印一下方便调试

        Scene scene = new Scene(webView, 1280, 800);
        primaryStage.setScene(scene);
        primaryStage.setTitle("电子邮件管理系统");
        primaryStage.setOnCloseRequest(event -> stop());
        primaryStage.show();
    }

    /**
     * 内部下载器
     */
    private void downloadFileInternally(String urlStr, File saveFile, String cookie) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);

            // 带上 Cookie
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }

            // 检查响应码，防止下载了 404/500 错误页面
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("服务器返回错误代码: " + responseCode);
            }

            try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(saveFile)) {

                byte[] buffer = new byte[4096]; // 稍微大一点的缓冲区
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("导出成功");
                alert.setHeaderText(null);
                alert.setContentText("文件已保存至:\n" + saveFile.getAbsolutePath());
                alert.show(); // 使用 show() 而不是 showAndWait() 防止极少数情况下的阻塞
            });

        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("导出失败");
                alert.setContentText("错误信息: " + e.getMessage());
                alert.show();
            });
        }
    }

    @Override
    public void stop() {
        if (springContext != null) springContext.close();
        Platform.exit();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
