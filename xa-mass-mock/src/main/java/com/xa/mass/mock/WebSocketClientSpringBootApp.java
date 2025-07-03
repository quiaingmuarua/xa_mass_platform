package com.xa.mass.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WebSocket 客户端模拟应用
 * 用于模拟多个设备客户端连接到 Mass Gateway
 */
@SpringBootApplication(scanBasePackages = {"com.xa.mass.gateway", "com.xa.mass.mock"})
public class WebSocketClientSpringBootApp {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClientSpringBootApp.class);

    public static void main(String[] args) {
        // 设置客户端环境配置
        String profile = "client";
        System.setProperty("spring.profiles.active", profile);
        String port = "8089"; // 客户端使用不同端口避免冲突
        System.setProperty("server.port", port);

        log.info("🔌 启动 WebSocket 客户端模拟应用...");
        log.info("Profile: {}, 端口: {}", profile, port);

        SpringApplication.run(WebSocketClientSpringBootApp.class, args);

        log.info("✅ WebSocket 客户端模拟应用启动完成");
        log.info("\n==============================");
        log.info("🔌 客户端服务地址:");
        log.info("   - 客户端状态: http://localhost:{}/status", port);
        log.info("   - 连接管理: http://localhost:{}/status/clients", port);
        log.info("   - 连接到: ws://localhost:18088/ws");
        log.info("==============================\n");
    }
}
