package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskShellStore;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class JdbcStorageRuntime implements AutoCloseable {

    private final JdbcStorageMode mode;
    private final HikariDataSource dataSource;
    private final JdbcDialect dialect;
    private final TaskShellStore taskShellStore;
    private final RuleStorage ruleStorage;

    private JdbcStorageRuntime(JdbcStorageMode mode,
                               HikariDataSource dataSource,
                               JdbcDialect dialect,
                               TaskShellStore taskShellStore,
                               RuleStorage ruleStorage) {
        this.mode = mode;
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.taskShellStore = taskShellStore;
        this.ruleStorage = ruleStorage;
    }

    public static JdbcStorageRuntime disabled() {
        return new JdbcStorageRuntime(JdbcStorageMode.MEMORY, null, null, null, null);
    }

    public static JdbcStorageRuntime create(JdbcStorageMode mode, String jdbcUrl, String username, String password) {
        if (mode == null || !mode.isJdbc()) {
            return disabled();
        }
        prepareFilesystemJdbcTarget(mode, jdbcUrl);
        HikariDataSource dataSource = createDataSource(jdbcUrl, username, password);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/control-plane")
                .load()
                .migrate();

        JdbcDialect dialect = mode.dialect();
        JdbcTaskShellStore taskShellStore = new JdbcTaskShellStore(dataSource, dialect);
        JdbcRuleStorage ruleStorage = new JdbcRuleStorage(dataSource, dialect);
        return new JdbcStorageRuntime(mode, dataSource, dialect, taskShellStore, ruleStorage);
    }

    private static HikariDataSource createDataSource(String jdbcUrl, String username, String password) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("mass.storage.jdbc.url must not be blank for JDBC storage modes");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setPoolName("xa-mass-jdbc-storage");
        return new HikariDataSource(config);
    }

    private static void prepareFilesystemJdbcTarget(JdbcStorageMode mode, String jdbcUrl) {
        String prefix = "jdbc:sqlite:";
        if (mode != JdbcStorageMode.JDBC_SQLITE || jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
            return;
        }
        String rawPath = jdbcUrl.substring(prefix.length()).trim();
        if (rawPath.isBlank() || rawPath.equals(":memory:") || rawPath.startsWith("file::memory:")) {
            return;
        }
        int queryIndex = rawPath.indexOf('?');
        if (queryIndex >= 0) {
            rawPath = rawPath.substring(0, queryIndex);
        }
        if (rawPath.startsWith("file:")) {
            rawPath = rawPath.substring("file:".length());
        }
        Path parent = Paths.get(rawPath).toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite storage directory: " + parent, e);
        }
    }

    public boolean isEnabled() {
        return mode.isJdbc();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcDialect dialect() {
        return dialect;
    }

    public TaskShellStore taskShellStore() {
        return taskShellStore;
    }

    public RuleStorage ruleStorage() {
        return ruleStorage;
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
