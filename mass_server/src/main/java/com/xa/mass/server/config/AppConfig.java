package com.xa.mass.server.config;


import com.xa.mass.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public CommandLineRunner commandLineRunner(WebSocketServer webSocketServer) {
        return args -> {
            try {
                logger.info("Starting WebSocket server via CommandLineRunner...");
                webSocketServer.start(); // This will no longer block
                logger.info("WebSocket server start initiated.");
            } catch (Exception e) {
                logger.error("Failed to start WebSocket server", e);
            }
        };
    }
}
