package com.xa.mass.server.config;

import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.flywaydb.core.Flyway;

import java.util.Objects;

public final class ServerControlPlaneMigrationRunner {

    public static final String LOCATION = "classpath:db/migration/server-control-plane";
    public static final String HISTORY_TABLE = "flyway_server_control_plane_schema_history";

    private final JdbcStorageRuntime jdbcStorageRuntime;

    public ServerControlPlaneMigrationRunner(JdbcStorageRuntime jdbcStorageRuntime) {
        this.jdbcStorageRuntime = Objects.requireNonNull(jdbcStorageRuntime, "jdbcStorageRuntime");
    }

    public boolean migrate() {
        if (!jdbcStorageRuntime.isEnabled()) {
            return false;
        }
        Flyway.configure()
                .dataSource(jdbcStorageRuntime.dataSource())
                .locations(LOCATION)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        return true;
    }
}
