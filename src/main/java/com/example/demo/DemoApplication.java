package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 应用的主启动类
 * 整个后端的入口点。
 */
// @SpringBootApplication 是一个核心组合注解，它包含了以下三个关键功能：
// 1. @SpringBootConfiguration: 标记当前类为配置类 (类似 XML 配置文件)。
// 2. @EnableAutoConfiguration: 开启自动配置。Spring Boot 会根据你 pom.xml 里的依赖自动配置环境。
//    例如：看到了 spring-boot-starter-web，它就会自动配置 Tomcat 和 Spring MVC。
// 3. @ComponentScan: 开启组件扫描。
//    默认扫描当前包 (com.example.demo) 及其所有子包下的 @Controller, @Service, @Repository, @Component 等注解。
//    这就是为什么你的 Controller 必须放在这个类的同级或子包下的原因。
@SpringBootApplication
public class DemoApplication {

    /**
     * Java 程序的标准入口方法
     */
    public static void main(String[] args) {
        // 启动 Spring Boot 应用
        // 参数1: 传入主类的 Class 对象，告诉 Spring 从哪里开始加载
        // 参数2: 命令行参数 (比如 --server.port=8081 可以覆盖默认配置)
        //
        // 这一行代码执行后，会发生以下魔法：
        // 1. 启动内置的 Tomcat 服务器 (默认端口 8080)
        // 2. 创建 IOC 容器 (ApplicationContext)
        // 3. 扫描并加载所有的 Bean (你的 Controller, Service 等)
        SpringApplication.run(DemoApplication.class, args);
    }

}