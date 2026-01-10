package com.example.demo;

/**
 * 【程序的真正入口】(Startup / Fake Main)
 * * ❓ 为什么需要这个类？为什么不直接把 DesktopLauncher 设为主类？
 * * 💡 原因：解决 "JavaFX runtime components are missing" 报错。
 * * 1. 背景：
 * DesktopLauncher 继承了 javafx.application.Application。
 * 在 JDK 9 之后，如果 JAR 包的主类（Main-Class）直接继承了 Application，
 * JVM 会在启动前强制检查 module-path（模块路径）。
 * * 2. 问题：
 * 当我们把项目打包成一个 "Fat JAR"（所有依赖都在一个 jar 里的胖包）时，
 * JavaFX 的依赖也在 classpath 里，而不是 module-path 里。
 * JVM 会认为找不到 JavaFX 组件，直接报错无法启动。
 * * 3. 解决方案（欺骗 JVM）：
 * 我们创建一个**普通的 Java 类**（不继承 Application）作为入口。
 * JVM 启动 Startup 时，发现它只是个普通类，就不进行模块检查。
 * 进入 main 方法后，再由代码去调用 DesktopLauncher。
 * 这时候类加载器已经初始化完毕，JavaFX 就能正常启动了。
 */
public class Startup {

    public static void main(String[] args) {
        // "桥接"调用：把启动任务移交给真正的 JavaFX 启动类
        DesktopLauncher.main(args);
    }
}