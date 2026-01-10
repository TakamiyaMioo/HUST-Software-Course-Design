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

/**
 * 桌面启动器 (Launcher)
 * 作用：将 Spring Boot Web 项目包裹在 JavaFX 窗口中运行，使其看起来像一个桌面软件。
 */
public class DesktopLauncher extends Application {

    // 保存 Spring Boot 的上下文，用于在关闭窗口时连带关闭后端服务
    private ConfigurableApplicationContext springContext;

    /**
     * 【生命周期：初始化】
     * 在 JavaFX 窗口显示之前执行。这里用于启动 Spring Boot 后端。
     */
    @Override
    public void init() {
        // 使用 SpringApplicationBuilder 启动 Spring Boot
        springContext = new SpringApplicationBuilder(DemoApplication.class)
                .headless(false) // 关键设置：允许 Spring 环境中存在 GUI 组件（虽然主要由 JavaFX 负责，但设为 false 更稳妥）
                .run();
    }

    /**
     * 【生命周期：启动】
     * JavaFX 的主入口，负责构建界面 (Stage -> Scene -> WebView)。
     */
    @Override
    public void start(Stage primaryStage) {
        // 1. 创建浏览器组件
        // WebView 是视图组件（显示网页），WebEngine 是核心引擎（处理逻辑、JS、网络）
        WebView webView = new WebView();
        WebEngine webEngine = webView.getEngine();

        // 设置隐式退出为 false：防止最后一个窗口关闭时 JavaFX 线程自动终止
        // 我们需要自己在 stop() 方法中手动控制退出逻辑
        Platform.setImplicitExit(false);

        // =======================================================
        // 2. JS 弹窗兼容处理
        // 默认情况下，WebView 会忽略网页中的 alert() 和 confirm()。
        // 必须手动映射到 JavaFX 的 Alert 弹窗。
        // =======================================================

        // 处理 JS 的 alert("xxx")
        webEngine.setOnAlert(event -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText(event.getData()); // 获取 JS 传来的文本
            alert.showAndWait(); // 阻塞等待用户点击确定
        });

        // 处理 JS 的 confirm("xxx")
        webEngine.setConfirmHandler(message -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("确认");
            alert.setHeaderText(null);
            alert.setContentText(message);
            Optional<ButtonType> result = alert.showAndWait();
            // 返回 true 表示点击了 OK，返回 false 表示点击了 Cancel
            return result.isPresent() && result.get() == ButtonType.OK;
        });

        // =======================================================
        // 3. 核心：URL 导航监听器 (拦截下载请求)
        // WebView 本身不支持下载文件，点击下载链接会跳转到一个空白页。
        // 我们必须监听地址变化，拦截特定后缀或路径，手动处理下载。
        // =======================================================
        webEngine.locationProperty().addListener((observable, oldUrl, newUrl) -> {
            if (newUrl != null && !newUrl.isEmpty()) {
                String lowUrl = newUrl.toLowerCase();

                // 排除预览链接 (preview)，只处理下载
                if (!lowUrl.contains("/preview")) {

                    // -------------------------------------------------------
                    // 【情况 A】导出/CSV：需要内部处理
                    // 场景：后端动态生成的文件（如导出通讯录），通常需要权限验证 (Cookie)。
                    // 策略：使用 Java 代码模拟 HTTP 请求下载，保存到用户选定的位置。
                    // -------------------------------------------------------
                    if (lowUrl.contains("export") || lowUrl.endsWith(".csv")) {

                        // 【关键修复】Platform.runLater
                        // JavaFX 的监听器可能在非 UI 线程触发，或者为了避免阻塞当前导航事件，
                        // 我们必须把“弹窗选择文件”和“页面回退”的操作放入 UI 队列稍后执行。
                        Platform.runLater(() -> {
                            // 1. 尝试从浏览器引擎中“偷”Cookie
                            // 因为下载请求需要登录状态，我们必须把 WebView 里的 JSESSIONID 拿出来传给下载器
                            String cookie = null;
                            try {
                                cookie = (String) webEngine.executeScript("document.cookie");
                            } catch (Exception e) {
                                System.err.println("获取Cookie失败(不影响下载尝试): " + e.getMessage());
                            }

                            // 2. 马上回退页面 (核心体验优化)
                            // 点击下载链接后，WebView 已经跳到了下载地址（通常是空白页），
                            // 我们必须立刻让它加载回之前的页面 (oldUrl)，让用户感觉没离开过当前页。
                            if (oldUrl != null) webEngine.load(oldUrl);

                            // 3. 弹出原生文件保存框 (Save Dialog)
                            FileChooser fileChooser = new FileChooser();
                            fileChooser.setTitle("导出通讯录");
                            fileChooser.setInitialFileName("contacts.csv"); // 默认文件名
                            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 文件", "*.csv"));

                            // 这里的 showSaveDialog 会阻塞 UI，直到用户选择路径
                            File file = fileChooser.showSaveDialog(primaryStage);

                            // 4. 启动后台线程下载
                            // 网络 IO 绝对不能在 UI 线程做，否则界面会卡死。
                            if (file != null) {
                                String finalCookie = cookie; // 变量必须是 final 才能传入 Lambda
                                new Thread(() -> downloadFileInternally(newUrl, file, finalCookie)).start();
                            }
                        });
                    }
                    // -------------------------------------------------------
                    // 【情况 B】普通静态附件：调用系统浏览器
                    // 场景：下载 zip, pdf, doc 等静态资源。
                    // 策略：WebView 处理下载太麻烦，直接丢给操作系统默认浏览器 (Chrome/Edge) 去下载。
                    // -------------------------------------------------------
                    else if (lowUrl.contains("/download") ||
                            lowUrl.endsWith(".zip") || lowUrl.endsWith(".rar") ||
                            lowUrl.endsWith(".doc") || lowUrl.endsWith(".docx") ||
                            lowUrl.endsWith(".xls") || lowUrl.endsWith(".xlsx") ||
                            lowUrl.endsWith(".pdf") || lowUrl.endsWith(".txt")) {

                        // 1. 同样需要回退页面，防止白屏
                        Platform.runLater(() -> {
                            if (oldUrl != null) webEngine.load(oldUrl);
                        });

                        // 2. 调用系统默认浏览器打开链接
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

        // =======================================================
        // 4. 动态端口处理
        // =======================================================

        // 获取 Spring Boot 实际启动的端口 (在 application.yml 里通常配置为 server.port=0 随机端口)
        // 这样可以防止端口被占用导致软件打不开。
        String port = springContext.getEnvironment().getProperty("local.server.port");

        // 拼接首页地址
        String url = "http://localhost:" + port + "/";

        // 加载页面
        webEngine.load(url);
        System.out.println("正在访问动态端口: " + port);

        // =======================================================
        // 5. 窗口设置
        // =======================================================
        Scene scene = new Scene(webView, 1280, 800); // 默认分辨率
        primaryStage.setScene(scene);
        primaryStage.setTitle("电子邮件管理系统");

        // 监听窗口右上角的 X 关闭按钮
        primaryStage.setOnCloseRequest(event -> stop());
        primaryStage.show();
    }

    /**
     * 【内部下载器实现】
     * 手动实现 HTTP GET 请求，将 InputStream 写入 File。
     * * @param urlStr 下载地址
     * @param saveFile 本地保存路径
     * @param cookie 用户的登录凭证 (非常重要，否则后端会拦截下载请求跳转到登录页)
     */
    private void downloadFileInternally(String urlStr, File saveFile, String cookie) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000); // 10秒超时

            // 把从 WebView 偷来的 Cookie 塞进请求头，伪装成浏览器
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }

            // 检查服务器响应 (200 OK)
            // 如果 Cookie 过期或权限不足，这里可能会返回 302 跳转或 401/403 错误
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("服务器返回错误代码: " + responseCode);
            }

            // 标准 IO 流拷贝：输入流 (网络) -> 缓冲区 -> 输出流 (文件)
            try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(saveFile)) {

                byte[] buffer = new byte[4096]; // 4KB 缓冲区
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            // 下载完成，通知用户
            // 注意：必须切回 UI 线程弹窗，否则报错 "Not on FX application thread"
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("导出成功");
                alert.setHeaderText(null);
                alert.setContentText("文件已保存至:\n" + saveFile.getAbsolutePath());
                alert.show(); // 使用 show() 非阻塞显示，体验更好
            });

        } catch (Exception e) {
            e.printStackTrace();
            // 下载失败提示
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("导出失败");
                alert.setContentText("错误信息: " + e.getMessage());
                alert.show();
            });
        }
    }

    /**
     * 【生命周期：停止】
     * 当窗口关闭时调用。
     */
    @Override
    public void stop() {
        // 1. 关闭 Spring Boot 后端服务 (释放数据库连接、Tomcat端口等)
        if (springContext != null) springContext.close();

        // 2. 关闭 JavaFX 线程
        Platform.exit();

        // 3. 强制终止 JVM (确保所有后台线程都被杀死)
        System.exit(0);
    }

    // 标准 Java 入口
    public static void main(String[] args) {
        launch(args);
    }
}