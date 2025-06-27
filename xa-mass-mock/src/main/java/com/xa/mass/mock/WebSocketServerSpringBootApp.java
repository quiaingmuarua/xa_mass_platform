package com.xa.mass.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = { "com.xa.mass.mock","com.xa.mass.api"})
public class WebSocketServerSpringBootApp {
    
    private static final Logger log = LoggerFactory.getLogger(WebSocketServerSpringBootApp.class);
    
    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "server");
        System.setProperty("server.port", "8088");
        SpringApplication.run(WebSocketServerSpringBootApp.class, args);
        
        log.info("\n==============================");
        log.info("Knife4j 文档地址: http://localhost:8088/doc.html");
        log.info("==============================\n");
    }
}
