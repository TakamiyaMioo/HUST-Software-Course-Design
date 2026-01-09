package com.example.demo;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public class DesktopLauncher extends Application {

    private ConfigurableApplicationContext springContext;

    @Override
    public void init() {
        // 关键点：这里启动 Spring Boot
        // DemoApplication.class 必须是你原本的那个启动类名字，如果不一样请修改
        springContext = new SpringApplicationBuilder(DemoApplication.class).run();
    }

    @Override
    public void start(Stage primaryStage) {
        // 创建浏览器组件
        WebView webView = new WebView();

        // 【注意】这里填你项目启动后的首页地址
        // 如果你的登录页是 /login，就写 http://localhost:8080/login
        // 如果你的 server.port 不是 8080，请修改端口
        webView.getEngine().load("http://localhost:8080/");

        // 创建窗口
        Scene scene = new Scene(webView, 1024, 768);
        primaryStage.setScene(scene);
        primaryStage.setTitle("电子邮件管理系统");

        // 显示窗口
        primaryStage.show();
    }

    @Override
    public void stop() {
        // 关闭窗口时，强制关闭 Spring Boot 和 Java 进程
        springContext.close();
        Platform.exit();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}