package com.xa.mass.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class XaMassServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(XaMassServerApplication.class, args);
    }
}
