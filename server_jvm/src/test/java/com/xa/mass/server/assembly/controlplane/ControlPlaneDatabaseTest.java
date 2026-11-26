package com.xa.mass.server.assembly.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlPlaneDatabaseTest {
    @TempDir Path temp;

    @Test
    void disabledDatabaseCreatesNoFileAndRejectsTransactions() throws Exception {
        try (var db = ControlPlaneDatabase.disabled()) {
            assertThat(db.isEnabled()).isFalse();
            assertThat(db.databasePath()).isNull();
            assertThatThrownBy(() -> db.inTransaction(connection -> null))
                    .isInstanceOf(IllegalStateException.class);
        }
        var blank = new ControlPlaneProperties("   ");
        assertThat(blank.enabled()).isFalse();
        assertThat(blank.resolvedDatabasePath()).isNull();
    }

    @Test
    void initializesWalSchemaVersionOneAndSurvivesReopen() throws Exception {
        Path dbPath = temp.resolve("nested/control-plane.sqlite");
        try (var db = ControlPlaneDatabase.open(dbPath)) {
            assertThat(db.isEnabled()).isTrue();
            assertThat(db.databasePath()).isEqualTo(dbPath.toAbsolutePath().normalize());
            assertThat(Files.exists(dbPath)).isTrue();
            db.inTransaction(connection -> {
                try (var stmt = connection.createStatement()) {
                    try (var version = stmt.executeQuery("PRAGMA user_version")) {
                        assertThat(version.next()).isTrue();
                        assertThat(version.getInt(1)).isEqualTo(ControlPlaneDatabase.SCHEMA_VERSION);
                    }
                    try (var mode = stmt.executeQuery("PRAGMA journal_mode")) {
                        assertThat(mode.next()).isTrue();
                        assertThat(mode.getString(1)).isEqualToIgnoringCase("wal");
                    }
                    try (var fk = stmt.executeQuery("PRAGMA foreign_keys")) {
                        assertThat(fk.next()).isTrue();
                        assertThat(fk.getInt(1)).isEqualTo(1);
                    }
                    stmt.execute("""
                            INSERT INTO cp_roles(role_id, ordinal, label, permissions_json, task_scope, project_scope, home)
                            VALUES ('admin', 0, 'Administrator', '["users.manage"]', 'all', 'all', '/runtime/workers')
                            """);
                }
                return null;
            });
        }
        try (var reopened = ControlPlaneDatabase.open(dbPath)) {
            int count = reopened.inTransaction(connection -> {
                try (var stmt = connection.createStatement();
                     var rows = stmt.executeQuery("SELECT COUNT(*) FROM cp_roles")) {
                    rows.next();
                    return rows.getInt(1);
                }
            });
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void rollsBackFailedTransactionsCleanly() throws Exception {
        Path dbPath = temp.resolve("rollback.sqlite");
        try (var db = ControlPlaneDatabase.open(dbPath)) {
            assertThatThrownBy(() -> db.inTransaction(connection -> {
                try (var stmt = connection.createStatement()) {
                    stmt.execute("""
                            INSERT INTO cp_roles(role_id, ordinal, label, permissions_json, task_scope, project_scope, home)
                            VALUES ('admin', 0, 'Administrator', '[]', 'all', 'all', '/')
                            """);
                }
                throw new SQLException("injected failure");
            })).isInstanceOf(SQLException.class);
            int count = db.inTransaction(connection -> {
                try (var stmt = connection.createStatement();
                     var rows = stmt.executeQuery("SELECT COUNT(*) FROM cp_roles")) {
                    rows.next();
                    return rows.getInt(1);
                }
            });
            assertThat(count).isZero();
        }
    }

    @Test
    void rejectsForeignVersionlessAndUnsupportedSchemaDatabases() throws Exception {
        Path foreign = temp.resolve("foreign.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + foreign);
             var stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE other_table (id TEXT PRIMARY KEY)");
        }
        assertThatThrownBy(() -> ControlPlaneDatabase.open(foreign))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("not empty");

        Path future = temp.resolve("future.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + future);
             var stmt = connection.createStatement()) {
            stmt.execute("PRAGMA user_version=99");
        }
        assertThatThrownBy(() -> ControlPlaneDatabase.open(future))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Unsupported");

        Path incomplete = temp.resolve("incomplete.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + incomplete);
             var stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE cp_roles (role_id TEXT PRIMARY KEY)");
            stmt.execute("PRAGMA user_version=1");
        }
        assertThatThrownBy(() -> ControlPlaneDatabase.open(incomplete))
                .isInstanceOf(SQLException.class);
    }
}
