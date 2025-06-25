package com.xa.mass.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.xa.mass.mock", "com.xa.mass.engine"})
public class MockTaskEngineSpringBootApp {
    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "mock-engine");
        SpringApplication.run(MockTaskEngineSpringBootApp.class, args);
    }
} 