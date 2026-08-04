package com.xa.mass.server;

import com.scalar.maven.webmvc.ScalarWebMvcController;
import com.scalar.maven.webmvc.SpringBootScalarProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties(SpringBootScalarProperties.class)
@Import(ScalarWebMvcController.class)
public class XaMassServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(XaMassServerApplication.class, args);
    }
}
