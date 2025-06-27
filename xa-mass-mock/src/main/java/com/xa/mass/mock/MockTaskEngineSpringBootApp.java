package com.xa.mass.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.xa.mass.mock", "com.xa.mass.engine"})
public class MockTaskEngineSpringBootApp {
    
    private static final Logger log = LoggerFactory.getLogger(MockTaskEngineSpringBootApp.class);
    
    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "mock-engine");
        log.info("启动Mock任务引擎应用...");
        SpringApplication.run(MockTaskEngineSpringBootApp.class, args);
        log.info("Mock任务引擎应用启动完成");
    }
} 