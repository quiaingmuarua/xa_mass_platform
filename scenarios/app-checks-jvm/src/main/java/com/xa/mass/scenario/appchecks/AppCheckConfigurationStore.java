package com.xa.mass.scenario.appchecks;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** One local configuration owner. No Runtime identities or execution records are stored here. */
final class AppCheckConfigurationStore implements AutoCloseable {
    record Snapshot(String fingerprint, AppCheckSettings configuration) {
        Snapshot(AppCheckSettings configuration) { this(fingerprintOf(configuration), configuration); }
        Map<String, Object> toMap() { return Map.of("fingerprint", fingerprint, "configuration", configuration.toMap()); }
    }
    private final Connection connection;
    private final Snapshot effective;
    private final AppCheckContracts contracts;
    private boolean closed;

    AppCheckConfigurationStore(Path path, Supplier<String> seed, AppCheckContracts contracts) {
        this.contracts = Objects.requireNonNull(contracts);
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
                        + "id INTEGER PRIMARY KEY CHECK (id = 1), document CLOB NOT NULL)");
            }
            var existing = read(false);
            if (existing == null) {
                var initial = AppCheckSettings.parse(Jsons.parseObject(seed.get()), contracts);
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("INSERT INTO app_check_configuration (id, document) VALUES (1, ?)")) {
                    statement.setString(1, Jsons.toJson(initial.toMap()));
                    statement.executeUpdate();
                }
                connection.commit();
                connection.setAutoCommit(true);
                existing = read(false);
            }
            effective = Objects.requireNonNull(existing);
        } catch (SQLException | IOException | RuntimeException failure) {
            if (opened != null) try { opened.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw AppCheckConfigurationException.unavailable("open", failure);
        }
    }

    Snapshot effective() { return effective; }

    synchronized Map<String, Object> view() {
        try { return view(Objects.requireNonNull(read(false), "Missing configuration")); }
        catch (SQLException | RuntimeException failure) { throw AppCheckConfigurationException.unavailable("load", failure); }
    }

    synchronized Map<String, Object> save(String expectedFingerprint, Object document) {
        final AppCheckSettings settings;
        try {
            if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("expectedFingerprint 必须是读取到的 SHA-256 指纹");
            settings = AppCheckSettings.parse(document, contracts);
        }
        catch (IllegalArgumentException invalid) {
            throw new AppCheckConfigurationException(400, AppCheckConfigurationException.Code.INVALID_CONFIGURATION,
                    "save", invalid.getMessage(), invalid);
        }
        boolean committed = false;
        try {
            if (closed) throw new SQLException("Store is closed");
            connection.setAutoCommit(false);
            // Lock the singleton row across comparison and update, including a same-content save.
            // No revision counter or stored history is needed for content-based concurrency control.
            var previous = Objects.requireNonNull(read(true), "Missing configuration");
            if (!previous.fingerprint().equals(expectedFingerprint)) throw conflict();
            try { settings.requireUpdateOf(previous.configuration()); }
            catch (IllegalArgumentException invalid) {
                throw new AppCheckConfigurationException(400, AppCheckConfigurationException.Code.INVALID_CONFIGURATION,
                        "save", invalid.getMessage(), invalid);
            }
            var next = new Snapshot(settings);
            if (previous.fingerprint().equals(next.fingerprint())) {
                connection.commit(); committed = true;
                return view(previous);
            }
            try (var statement = connection.prepareStatement("UPDATE app_check_configuration SET document = ? WHERE id = 1")) {
                statement.setString(1, Jsons.toJson(settings.toMap()));
                if (statement.executeUpdate() != 1) throw conflict();
            }
            connection.commit(); committed = true;
            return view(next);
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

    private Snapshot read(boolean forUpdate) throws SQLException {
        if (closed) throw new SQLException("Store is closed");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT document FROM app_check_configuration WHERE id = 1" + (forUpdate ? " FOR UPDATE" : ""))) {
            if (!rows.next()) return null;
            return new Snapshot(AppCheckSettings.parse(Jsons.parseObject(rows.getString(1)), contracts));
        }
    }

    private static String fingerprintOf(AppCheckSettings settings) {
        try {
            byte[] canonical = Jsons.toJson(canonical(settings.toMap())).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put((String) key, canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(AppCheckConfigurationStore::canonical).toList();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }

    private Map<String, Object> view(Snapshot saved) {
        boolean changedWindow = effective.configuration().poolGroups().entrySet().stream().anyMatch(entry ->
                saved.configuration().poolGroups().containsKey(entry.getKey())
                        && saved.configuration().poolGroups().get(entry.getKey()).windowMillis() != entry.getValue().windowMillis());
        return Map.of("saved", saved.toMap(), "effective", effective.toMap(),
                "restartRequired", !saved.fingerprint().equals(effective.fingerprint()),
                "newScopeRequired", changedWindow, "checkTypes", contracts.descriptions());
    }

    private static AppCheckConfigurationException conflict() {
        return new AppCheckConfigurationException(409, AppCheckConfigurationException.Code.CONFIGURATION_CONFLICT,
                "save", "配置内容已变化，请重新读取后核对；本次未覆盖配置", null);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { connection.close(); }
        catch (SQLException failure) { throw AppCheckConfigurationException.unavailable("close", failure); }
    }
}
