package com.xa.mass.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Optional client-only bootstrap for isolated mock device startup.
 * This path is not the verified mainline runtime entry.
 */
@SpringBootApplication(scanBasePackages = {"com.xa.mass.mock"})
public class WebSocketClientSpringBootApp {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClientSpringBootApp.class);

    public static void main(String[] args) {
        String profile = System.getProperty("spring.profiles.active");
        if (profile == null || profile.isBlank()) {
            profile = "client";
            System.setProperty("spring.profiles.active", profile);
        }

        log.info("Starting client-only mock WebSocket bootstrap");
        log.info("Active profile: {}", profile);

        ConfigurableApplicationContext context = new SpringApplicationBuilder(WebSocketClientSpringBootApp.class)
                .web(WebApplicationType.NONE)
                .run(args);
        Environment environment = context.getEnvironment();
        String targetUri = environment.getProperty("mock.client.uri", "ws://localhost:18088/ws");

        log.info("Client-only mock bootstrap started");
        log.info("==============================");
        log.info("Spring Web server: disabled");
        log.info("Target gateway: {}", targetUri);
        log.info("==============================");
    }
}
