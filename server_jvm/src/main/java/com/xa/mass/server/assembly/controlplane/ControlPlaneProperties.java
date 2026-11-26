package com.xa.mass.server.assembly.controlplane;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional Server control-plane SQLite database.
 *
 * <p>Stores only low-frequency Server control-plane metadata (starting with the user directory).
 * Hot scheduling, execution, Matching and active Task data remain exclusively in Redis.
 */
@ConfigurationProperties(prefix = "xa.mass.control-plane", ignoreUnknownFields = false)
public record ControlPlaneProperties(@Nullable String databasePath) {

    public ControlPlaneProperties {
        if (databasePath == null || databasePath.isBlank()) {
            databasePath = null;
        } else {
            try {
                databasePath = Path.of(databasePath.strip()).toAbsolutePath().normalize().toString();
            } catch (InvalidPathException invalid) {
                throw new IllegalArgumentException("Control-plane database path is invalid", invalid);
            }
        }
    }

    public boolean enabled() {
        return databasePath != null;
    }

    public @Nullable Path resolvedDatabasePath() {
        return databasePath == null ? null : Path.of(databasePath);
    }
}
