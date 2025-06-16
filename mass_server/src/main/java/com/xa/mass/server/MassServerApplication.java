package com.xa.mass.server;

import com.xa.mass.server.service.WebSocketMessageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class MassServerApplication {
    private static final Logger logger = LoggerFactory.getLogger(MassServerApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(MassServerApplication.class, args);

        // 检查 WebSocketMessageProcessor 是否已加载并初始化
        try {
            WebSocketMessageProcessor processor = context.getBean(WebSocketMessageProcessor.class);
            if (processor != null) {
                logger.info("✅ WebSocketMessageProcessor bean is successfully loaded and available in the application context.");
            } else {
                // 这段逻辑实际上不会执行，因为如果bean不存在，getBean会抛异常
                logger.info("WebSocketMessageProcessor bean is not loaded");
            }
        } catch (Exception e) {
            logger.error("❌ Failed to retrieve WebSocketMessageProcessor bean from the application context.", e);
        }


        logger.info("MassServerApplication started");
    }
}