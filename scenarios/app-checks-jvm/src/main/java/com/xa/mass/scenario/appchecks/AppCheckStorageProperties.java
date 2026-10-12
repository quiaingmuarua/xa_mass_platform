package com.xa.mass.scenario.appchecks;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xa.mass.scenarios.app-checks", ignoreUnknownFields = false)
public record AppCheckStorageProperties(Storage storage) {
    public AppCheckStorageProperties {
        if (storage == null) throw new IllegalArgumentException("App Checks storage must be configured");
    }
    public record Storage(String path, String seed) {
        public Storage {
            if (path == null || path.isBlank() || path.contains(";") || path.contains("${")
                    || seed == null || seed.isBlank() || seed.contains("${"))
                throw new IllegalArgumentException("App Checks storage.path and storage.seed must be explicit");
        }
    }
}
