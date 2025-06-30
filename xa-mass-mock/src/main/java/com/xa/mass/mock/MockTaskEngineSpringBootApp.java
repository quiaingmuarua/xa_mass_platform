package com.xa.mass.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = { "com.xa.mass.mock", "com.xa.mass.api" })
public class MockTaskEngineSpringBootApp {
    
    private static final Logger log = LoggerFactory.getLogger(MockTaskEngineSpringBootApp.class);
    
    public static void main(String[] args) {
        // 支持通过命令行参数或环境变量切换 profile 和端口
        String profile = "dev";
        System.setProperty("spring.profiles.active",profile);
        String port ="8088";
        System.setProperty("server.port", port);
        log.info("启动Mock任务引擎应用...");
        SpringApplication.run(MockTaskEngineSpringBootApp.class, args);
        log.info("Mock任务引擎应用启动完成");
        log.info("\n==============================");
        log.info("Mock Server 启动成功，Profile: {}，端口: {}", profile, port);
        log.info("访问状态页: http://localhost:{}/status", port);
        log.info("规则管理页: http://localhost:{}/status/rules", port);
        log.info("Knife4j 文档地址: http://localhost:{}/doc.html", port);
        log.info("==============================\n");
    }
} 