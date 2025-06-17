package com.xa.mass.core.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for mass_core module
 * This class enables component scanning for the core module
 */
@Configuration
@ComponentScan(basePackages = "com.xa.mass.core")
public class MassCoreConfig {
    // Configuration properties and beans can be added here
} 