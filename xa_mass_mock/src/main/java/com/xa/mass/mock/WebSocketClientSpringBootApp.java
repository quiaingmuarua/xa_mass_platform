package com.xa.mass.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.xa.mass.core", "com.xa.mass.mock"})
public class WebSocketClientSpringBootApp {
    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "client");
        SpringApplication.run(WebSocketClientSpringBootApp.class, args);
    }
}
