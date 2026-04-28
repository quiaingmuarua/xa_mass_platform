package com.xa.mass.server.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.engine.rules.RuleConfig;
import com.xa.mass.engine.rules.RuleManager;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.util.Map;

public final class JdbcStorageRuntime implements AutoCloseable {

    private final JdbcStorageMode mode;
    private final HikariDataSource dataSource;
    private final JdbcDialect dialect;
    private final JdbcTaskStorage taskStorage;
    private final JdbcWorkerStorage workerStorage;
    private final RuleManager<Map<String, Object>> ruleManager;
    private final JdbcRuntimeResidueRecovery residueRecovery;

    private JdbcStorageRuntime(JdbcStorageMode mode,
                               HikariDataSource dataSource,
                               JdbcDialect dialect,
                               JdbcTaskStorage taskStorage,
                               JdbcWorkerStorage workerStorage,
                               RuleManager<Map<String, Object>> ruleManager) {
        this.mode = mode;
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.taskStorage = taskStorage;
        this.workerStorage = workerStorage;
        this.ruleManager = ruleManager;
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
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(ruleStorage);
        ruleManager.addDefaultRules(RuleConfig.getDefaultWorkerMatchRules());
        return new JdbcStorageRuntime(mode, dataSource, dialect, taskStorage, workerStorage, ruleManager);
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

    public JdbcTaskStorage taskStorage() {
        return taskStorage;
    }

    public JdbcWorkerStorage workerStorage() {
        return workerStorage;
    }

    public RuleManager<Map<String, Object>> ruleManager() {
        return ruleManager;
    }

    public void recoverRuntimeResidue() {
        if (!isEnabled()) {
            return;
        }
        residueRecovery.recover(taskStorage, workerStorage);
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
