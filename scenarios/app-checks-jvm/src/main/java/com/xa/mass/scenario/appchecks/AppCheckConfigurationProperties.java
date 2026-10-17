package com.xa.mass.scenario.appchecks;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xa.mass.scenarios.app-checks", ignoreUnknownFields = false)
public record AppCheckConfigurationProperties(Configuration configuration) {
    public AppCheckConfigurationProperties {
        if (configuration == null) throw new IllegalArgumentException("App Checks configuration must be configured");
    }
    public record Configuration(String path, String seed) {
        public Configuration {
            if (path == null || path.isBlank() || path.contains("${")
                    || seed == null || seed.isBlank() || seed.contains("${"))
                throw new IllegalArgumentException("App Checks configuration.path and configuration.seed must be explicit");
        }
    }
}
