package com.xa.mass.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.xa.mass.gateway", "com.xa.mass.mock"})
public class WebSocketServerSpringBootApp {
    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "server");
        System.setProperty("server.port", "8088");
        SpringApplication.run(WebSocketServerSpringBootApp.class, args);
        System.out.println("\n==============================");
        System.out.println("Knife4j 文档地址: http://localhost:8088/doc.html");
        System.out.println("==============================\n");
    }
}
