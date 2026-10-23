package io.github.chocoemmm.danmakulab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 服务启动入口。Spring Boot 从本类所在包向下扫描配置、组件和服务。
 * ConfigurationPropertiesScan 另外扫描配置映射类，把 application.yml 中的 lab 配置交给 LabProperties。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DanmakuLabApplication {
    public static void main(String[] args) {
        // 创建 Spring 容器、启动内嵌 Tomcat，并注册静态页面和 WebSocket 端点。
        SpringApplication.run(DanmakuLabApplication.class, args);
    }
}
