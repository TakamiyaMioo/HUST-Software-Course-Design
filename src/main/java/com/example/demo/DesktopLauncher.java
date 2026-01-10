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
                    // 【情况 A】如果是 CSV 导出或需要鉴权的下载 -> 使用内部下载器
                    // =======================================================
                    if (lowUrl.contains("export") || lowUrl.endsWith(".csv")) {

                        // 1. 获取当前 WebView 里的 Cookie (这是关键！有了它后端才知道你是谁)
                        String cookie = (String) webEngine.executeScript("document.cookie");

                        // 2. 防止 WebView 跳转白屏
                        Platform.runLater(() -> {
                            if (oldUrl != null) webEngine.load(oldUrl);
                        });

                        // 3. 弹出保存文件对话框 (必须在 JavaFX 线程)
                        FileChooser fileChooser = new FileChooser();
                        fileChooser.setTitle("保存文件");
                        fileChooser.setInitialFileName("contacts.csv");
                        File file = fileChooser.showSaveDialog(primaryStage);

                        // 4. 如果用户选了位置，开始下载
                        if (file != null) {
                            new Thread(() -> downloadFileInternally(newUrl, file, cookie)).start();
                        }
                    }
                    // =======================================================
                    // 【情况 B】如果是普通附件 -> 依然调用系统浏览器 (体验更好)
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

        webEngine.load("http://localhost:8080/");

        Scene scene = new Scene(webView, 1280, 800);
        primaryStage.setScene(scene);
        primaryStage.setTitle("电子邮件管理系统");
        primaryStage.setOnCloseRequest(event -> stop());
        primaryStage.show();
    }

    /**
     * 【新增】内部下载器方法
     * 它会带上 Cookie 发起请求，并将文件保存到用户指定的位置
     */
    private void downloadFileInternally(String urlStr, File saveFile, String cookie) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            // 关键：把 WebView 的 Cookie 塞给请求头
            if (cookie != null) {
                conn.setRequestProperty("Cookie", cookie);
            }

            // 开始读取数据并写入文件
            try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(saveFile)) {

                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer, 0, 1024)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            // 下载完成后提示用户
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("下载完成");
                alert.setHeaderText(null);
                alert.setContentText("文件已成功保存到:\n" + saveFile.getAbsolutePath());
                alert.show();
            });

        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("下载失败");
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
