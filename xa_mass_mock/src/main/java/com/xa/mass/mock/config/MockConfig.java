package com.xa.mass.mock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "mock")
public class MockConfig {
    private Client client = new Client();
    private Task task = new Task();

    @Data
    public static class Client {
        private int count = 5;
        private String uri = "ws://localhost:8088/ws";
    }

    @Data
    public static class Task {
        private long interval = 30000;
    }
} 
