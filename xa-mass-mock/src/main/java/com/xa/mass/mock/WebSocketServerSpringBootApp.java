package com.xa.mass.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.AbstractEnvironment;

@SpringBootApplication(scanBasePackages = { "com.xa.mass.mock", "com.xa.mass.api" })
public class WebSocketServerSpringBootApp {
    private static final Logger log = LoggerFactory.getLogger(WebSocketServerSpringBootApp.class);

    public static void main(String[] args) {
        // 支持通过命令行参数或环境变量切换 profile 和端口
        String profile = System.getProperty("spring.profiles.active", System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "mock"));
        String port = System.getProperty("server.port", System.getenv().getOrDefault("SERVER_PORT", "8088"));
        System.setProperty(AbstractEnvironment.ACTIVE_PROFILES_PROPERTY_NAME, profile);
        System.setProperty("server.port", port);
//        SpringApplication.run(WebSocketServerSpringBootApp.class, args);
        SpringApplication.run(WebSocketServerSpringBootApp.class, args);
        log.info("\n==============================");
        log.info("Mock Server 启动成功，Profile: {}，端口: {}", profile, port);
        log.info("访问状态页: http://localhost:{}/status", port);
        log.info("Knife4j 文档地址: http://localhost:{}/doc.html", port);
        log.info("==============================\n");
    }
}
