package com.xa.mass.server.assembly.controlplane;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Shared Server-owned SQLite database for low-frequency control-plane metadata.
 *
 * <p>Disabled when {@code xa.mass.control-plane.database-path} is unset or blank so existing file/classpath
 * configurations and unit tests create no database files. Never participates in Kernel, Pacer, Matching
 * or execution hot paths.
 */
@Service
public final class ControlPlaneDatabase implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;

    private final @Nullable Path databasePath;
    private final @Nullable Connection writer;
    private boolean closed;

    @Autowired
    public ControlPlaneDatabase(ControlPlaneProperties properties) throws IOException, SQLException {
        Objects.requireNonNull(properties, "properties");
        this.databasePath = properties.resolvedDatabasePath();
        if (this.databasePath == null) {
            this.writer = null;
            return;
        }
        Path parent = this.databasePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = connect("jdbc:sqlite:" + this.databasePath);
        try {
            initialize();
        } catch (SQLException | RuntimeException failure) {
            try {
                this.writer.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public static ControlPlaneDatabase open(Path databasePath) throws IOException, SQLException {
        Objects.requireNonNull(databasePath, "databasePath");
        return new ControlPlaneDatabase(new ControlPlaneProperties(databasePath.toString()));
    }

    public static ControlPlaneDatabase disabled() {
        try {
            return new ControlPlaneDatabase(new ControlPlaneProperties(null));
        } catch (IOException | SQLException impossible) {
            throw new IllegalStateException("Disabled control-plane database cannot fail initialization", impossible);
        }
    }

    public boolean isEnabled() {
        return databasePath != null;
    }

    public @Nullable Path databasePath() {
        return databasePath;
    }

    public synchronized <T> T inTransaction(SqlTransaction<T> work) throws SQLException {
        Objects.requireNonNull(work, "work");
        if (writer == null) {
            throw new IllegalStateException("Control-plane database is disabled");
        }
        if (closed) {
            throw new SQLException("Control-plane database is closed");
        }
        writer.setAutoCommit(false);
        try {
            T result = work.execute(writer);
            writer.commit();
            return result;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                writer.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            writer.setAutoCommit(true);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        if (writer != null) {
            writer.close();
        }
    }

    private static Connection connect(String url) throws SQLException {
        var connection = DriverManager.getConnection(url);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=FULL");
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    private void initialize() throws SQLException {
        assert writer != null;
        try (var statement = writer.createStatement()) {
            int version;
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                rows.next();
                version = rows.getInt(1);
            }
            if (version != 0 && version != SCHEMA_VERSION) {
                throw new SQLException("Unsupported control-plane schema version: " + version);
            }
            if (version == 0) {
                inTransaction(connection -> {
                    try (var schema = connection.createStatement()) {
                        // A versionless non-empty SQLite file (e.g. task-history.sqlite) must never be adopted.
                        try (var tables = schema.executeQuery(
                                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                            if (tables.next()) {
                                throw new SQLException("Control-plane database is not empty and has no schema version");
                            }
                        }
                        schema.execute("""
                                CREATE TABLE cp_roles (
                                  role_id TEXT PRIMARY KEY,
                                  ordinal INTEGER NOT NULL UNIQUE,
                                  label TEXT NOT NULL,
                                  permissions_json TEXT NOT NULL,
                                  task_scope TEXT NOT NULL,
                                  project_scope TEXT NOT NULL,
                                  home TEXT NOT NULL
                                )
                                """);
                        schema.execute("""
                                CREATE TABLE cp_users (
                                  user_id TEXT PRIMARY KEY,
                                  ordinal INTEGER NOT NULL UNIQUE,
                                  display_name TEXT NOT NULL,
                                  role_id TEXT NOT NULL REFERENCES cp_roles(role_id),
                                  access_key_sha256 TEXT UNIQUE
                                )
                                """);
                        schema.execute("""
                                CREATE TABLE cp_user_projects (
                                  user_id TEXT NOT NULL REFERENCES cp_users(user_id) ON DELETE CASCADE,
                                  ordinal INTEGER NOT NULL,
                                  project_id TEXT NOT NULL,
                                  PRIMARY KEY (user_id, project_id),
                                  UNIQUE (user_id, ordinal)
                                )
                                """);
                        schema.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                    }
                    return null;
                });
            }
            // Fail fast at startup if an existing schema is missing required columns.
            statement.executeQuery(
                    "SELECT role_id, ordinal, label, permissions_json, task_scope, project_scope, home FROM cp_roles LIMIT 0"
            ).close();
            statement.executeQuery(
                    "SELECT user_id, ordinal, display_name, role_id, access_key_sha256 FROM cp_users LIMIT 0"
            ).close();
            statement.executeQuery(
                    "SELECT user_id, ordinal, project_id FROM cp_user_projects LIMIT 0"
            ).close();
            try (var mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) {
                    throw new SQLException("Control-plane database requires WAL mode");
                }
            }
        }
    }

    @FunctionalInterface
    public interface SqlTransaction<T> {
        T execute(Connection connection) throws SQLException;
    }
}
