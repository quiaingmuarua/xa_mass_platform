package com.xa.mass.server.config;

import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ServerControlPlaneMigrationRunnerTest {

    @Test
    void memoryStorageModeDoesNotRunServerOwnedMigrations() {
        ServerControlPlaneMigrationRunner runner = new ServerControlPlaneMigrationRunner(JdbcStorageRuntime.disabled());

        assertFalse(runner.migrate());
    }
}
