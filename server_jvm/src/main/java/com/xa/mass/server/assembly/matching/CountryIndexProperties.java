package com.xa.mass.server.assembly.matching;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xa.mass.worker-matching.country-index", ignoreUnknownFields = false)
public record CountryIndexProperties(Set<String> workerGroups) {
    public CountryIndexProperties {
        workerGroups = workerGroups == null ? Set.of() : Set.copyOf(workerGroups);
        if (workerGroups.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("country index WorkerGroups must be non-blank");
        }
    }
}
