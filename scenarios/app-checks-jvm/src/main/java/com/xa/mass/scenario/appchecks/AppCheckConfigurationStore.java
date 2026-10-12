package com.xa.mass.scenario.appchecks;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** One local configuration owner. No Runtime identities or execution records are stored here. */
final class AppCheckConfigurationStore implements AutoCloseable {
    private static final int FORMAT = 1;
    record Snapshot(long revision, AppCheckSettings configuration) {
        Map<String, Object> toMap() { return Map.of("revision", revision, "configuration", configuration.toMap()); }
    }
    private final Connection connection;
    private final Snapshot effective;
    private boolean closed;

    AppCheckConfigurationStore(Path path, Supplier<String> seed) {
        Connection opened = null;
        try {
            Path absolute = path.toAbsolutePath().normalize();
            if (absolute.toString().contains(";")) throw new IllegalArgumentException("Invalid H2 file path");
            Files.createDirectories(absolute.getParent());
            opened = DriverManager.getConnection("jdbc:h2:file:" + absolute.toString().replace('\\', '/')
                    + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
            connection = opened;
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS app_check_configuration ("
                        + "id INTEGER PRIMARY KEY CHECK (id = 1), format_version INTEGER NOT NULL, "
                        + "revision BIGINT NOT NULL CHECK (revision > 0), document CLOB NOT NULL)");
            }
            var existing = read();
            if (existing == null) {
                var initial = AppCheckSettings.parse(Jsons.parseObject(seed.get()));
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("INSERT INTO app_check_configuration VALUES (1, ?, 1, ?)")) {
                    statement.setInt(1, FORMAT);
                    statement.setString(2, Jsons.toJson(initial.toMap()));
                    statement.executeUpdate();
                }
                connection.commit();
                connection.setAutoCommit(true);
                existing = read();
            }
            effective = Objects.requireNonNull(existing);
        } catch (SQLException | IOException | RuntimeException failure) {
            if (opened != null) try { opened.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw AppCheckConfigurationException.unavailable("open", failure);
        }
    }

    Snapshot effective() { return effective; }

    synchronized Map<String, Object> view() {
        try { return view(Objects.requireNonNull(read(), "Missing configuration")); }
        catch (SQLException | RuntimeException failure) { throw AppCheckConfigurationException.unavailable("load", failure); }
    }

    synchronized Map<String, Object> save(long expectedRevision, AppCheckSettings settings) {
        boolean committed = false;
        try {
            if (closed) throw new SQLException("Store is closed");
            connection.setAutoCommit(false);
            var previous = Objects.requireNonNull(read(), "Missing configuration");
            if (previous.revision() != expectedRevision) throw conflict();
            try { settings.requireUpdateOf(previous.configuration()); }
            catch (IllegalArgumentException invalid) {
                throw new AppCheckConfigurationException(400, AppCheckConfigurationException.Code.INVALID_CONFIGURATION,
                        "save", invalid.getMessage(), invalid);
            }
            if (previous.configuration().equals(settings)) {
                connection.commit(); committed = true;
                return view(previous);
            }
            long next = Math.addExact(previous.revision(), 1);
            try (var statement = connection.prepareStatement("UPDATE app_check_configuration SET revision = ?, document = ? WHERE id = 1 AND revision = ?")) {
                statement.setLong(1, next);
                statement.setString(2, Jsons.toJson(settings.toMap()));
                statement.setLong(3, expectedRevision);
                if (statement.executeUpdate() != 1) throw conflict();
            }
            connection.commit(); committed = true;
            return view(new Snapshot(next, settings));
        } catch (AppCheckConfigurationException known) { throw known;
        } catch (SQLException | RuntimeException failure) {
            throw AppCheckConfigurationException.unavailable("save", failure);
        } finally {
            try {
                if (!committed) connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException failure) {
                try { close(); } catch (RuntimeException close) { failure.addSuppressed(close); }
                throw AppCheckConfigurationException.unavailable("release", failure);
            }
        }
    }

    private Snapshot read() throws SQLException {
        if (closed) throw new SQLException("Store is closed");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT format_version, revision, document FROM app_check_configuration WHERE id = 1")) {
            if (!rows.next()) return null;
            if (rows.getInt(1) != FORMAT) throw new IllegalArgumentException("Unsupported App Checks configuration format");
            return new Snapshot(rows.getLong(2), AppCheckSettings.parse(Jsons.parseObject(rows.getString(3))));
        }
    }

    private Map<String, Object> view(Snapshot saved) {
        boolean changedWindow = effective.configuration().poolGroups().entrySet().stream().anyMatch(entry ->
                saved.configuration().poolGroups().containsKey(entry.getKey())
                        && saved.configuration().poolGroups().get(entry.getKey()).windowMillis() != entry.getValue().windowMillis());
        return Map.of("saved", saved.toMap(), "effective", effective.toMap(),
                "restartRequired", !saved.configuration().equals(effective.configuration()),
                "newScopeRequired", changedWindow);
    }

    private static AppCheckConfigurationException conflict() {
        return new AppCheckConfigurationException(409, AppCheckConfigurationException.Code.CONFIGURATION_CONFLICT,
                "save", "配置修订已变化，请重新读取后核对；本次未覆盖配置", null);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { connection.close(); }
        catch (SQLException failure) { throw AppCheckConfigurationException.unavailable("close", failure); }
    }
}
