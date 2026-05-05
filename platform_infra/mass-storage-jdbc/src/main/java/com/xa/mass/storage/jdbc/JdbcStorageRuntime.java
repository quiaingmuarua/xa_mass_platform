package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.storage.api.WorkerStorage;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

public final class JdbcStorageRuntime implements AutoCloseable {

    private final JdbcStorageMode mode;
    private final HikariDataSource dataSource;
    private final JdbcDialect dialect;
    private final TaskStorage taskStorage;
    private final WorkerStorage workerStorage;
    private final RuleStorage ruleStorage;
    private final JdbcRuntimeResidueRecovery residueRecovery;

    private JdbcStorageRuntime(JdbcStorageMode mode,
                               HikariDataSource dataSource,
                               JdbcDialect dialect,
                               TaskStorage taskStorage,
                               WorkerStorage workerStorage,
                               RuleStorage ruleStorage) {
        this.mode = mode;
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.taskStorage = taskStorage;
        this.workerStorage = workerStorage;
        this.ruleStorage = ruleStorage;
        this.residueRecovery = new JdbcRuntimeResidueRecovery();
    }

    public static JdbcStorageRuntime disabled() {
        return new JdbcStorageRuntime(JdbcStorageMode.MEMORY, null, null, null, null, null);
    }

    public static JdbcStorageRuntime create(JdbcStorageMode mode, String jdbcUrl, String username, String password) {
        if (mode == null || !mode.isJdbc()) {
            return disabled();
        }
        HikariDataSource dataSource = createDataSource(jdbcUrl, username, password);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/control-plane")
                .load()
                .migrate();

        JdbcDialect dialect = mode.dialect();
        JdbcTaskStorage taskStorage = new JdbcTaskStorage(dataSource, dialect);
        JdbcWorkerStorage workerStorage = new JdbcWorkerStorage(dataSource, dialect);
        JdbcRuleStorage ruleStorage = new JdbcRuleStorage(dataSource, dialect);
        return new JdbcStorageRuntime(mode, dataSource, dialect, taskStorage, workerStorage, ruleStorage);
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

    public boolean isEnabled() {
        return mode.isJdbc();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcDialect dialect() {
        return dialect;
    }

    public TaskStorage taskStorage() {
        return taskStorage;
    }

    public WorkerStorage workerStorage() {
        return workerStorage;
    }

    public RuleStorage ruleStorage() {
        return ruleStorage;
    }

    public void recoverRuntimeResidue() {
        if (!isEnabled()) {
            return;
        }
        residueRecovery.recover(workerStorage);
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}

