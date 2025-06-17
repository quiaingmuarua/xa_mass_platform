package com.xa.mass.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.xa.mass.core", "com.xa.mass.mock"})
public class WebSocketServerSpringBootApp {
    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "server");
        SpringApplication.run(WebSocketServerSpringBootApp.class, args);
    }
}
