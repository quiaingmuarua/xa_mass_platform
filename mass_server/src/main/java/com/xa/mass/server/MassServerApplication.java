package com.xa.mass.server;

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

        logger.info("MassServerApplication started");
    }
}