package com.xa.mass.server.config;

import com.xa.mass.storage.jdbc.JdbcStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSchemaResetGuardTest {

    @TempDir
    private Path tempDir;

    private final LocalSchemaResetGuard guard = new LocalSchemaResetGuard();

    @Test
    void freshDurableLocalSqliteWritesSidecarBeforeDatabaseExists() {
        Path db = tempDir.resolve("xa_mass.db");

        guard.verify(durableLocal(), JdbcStorageMode.JDBC_SQLITE, sqliteUrl(db), true, false);

        assertThat(Files.exists(db)).isFalse();
        assertThat(Files.exists(metadata(db))).isTrue();
    }

    @Test
    void existingLocalSqliteWithoutSidecarFailsByDefault() throws IOException {
        Path db = tempDir.resolve("xa_mass.db");
        Files.writeString(db, "old-db");

        assertThatThrownBy(() ->
                guard.verify(durableLocal(), JdbcStorageMode.JDBC_SQLITE, sqliteUrl(db), true, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local SQLite schema fingerprint mismatch")
                .hasMessageContaining("mass.local-schema-reset.reset-on-mismatch=true");

        assertThat(Files.exists(db)).isTrue();
    }

    @Test
    void explicitLocalResetDeletesStaleDatabaseAndWritesNewSidecar() throws IOException {
        Path db = tempDir.resolve("xa_mass.db");
        Files.writeString(db, "old-db");

        guard.verify(durableLocal(), JdbcStorageMode.JDBC_SQLITE, sqliteUrl(db), true, true);

        assertThat(Files.exists(db)).isFalse();
        assertThat(Files.exists(metadata(db))).isTrue();
        assertThat(Files.readString(metadata(db))).isNotBlank();
    }

    @Test
    void nonAllowlistedProfileCannotReset() {
        Path db = tempDir.resolve("xa_mass.db");

        assertThatThrownBy(() ->
                guard.verify(new String[]{"memory-local"}, JdbcStorageMode.JDBC_SQLITE, sqliteUrl(db), true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowlisted local profiles");
    }

    @Test
    void postgresTargetCannotReset() {
        assertThatThrownBy(() ->
                guard.verify(durableLocal(), JdbcStorageMode.JDBC_POSTGRES,
                        "jdbc:postgresql://localhost:5432/xa_mass", true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable-local SQLite file targets");
    }

    @Test
    void disabledGuardDoesNotWriteSidecar() {
        Path db = tempDir.resolve("xa_mass.db");

        guard.verify(durableLocal(), JdbcStorageMode.JDBC_SQLITE, sqliteUrl(db), false, false);

        assertThat(Files.exists(metadata(db))).isFalse();
    }

    private static String[] durableLocal() {
        return new String[]{"durable-local"};
    }

    private static String sqliteUrl(Path db) {
        return "jdbc:sqlite:" + db;
    }

    private static Path metadata(Path db) {
        return db.resolveSibling(db.getFileName() + ".schema.sha256");
    }
}
