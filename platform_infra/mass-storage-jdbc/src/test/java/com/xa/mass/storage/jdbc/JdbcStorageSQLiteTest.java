package com.xa.mass.storage.jdbc;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcStorageSQLiteTest {

    @Test
    void runtimeCreatesSqliteFileAndPersistsRuleTruth() throws Exception {
        var db = Files.createTempDirectory("xa-mass-sqlite-runtime").resolve("xa_mass.db");
        try (JdbcStorageRuntime runtime = JdbcStorageRuntime.create(
                JdbcStorageMode.JDBC_SQLITE,
                "jdbc:sqlite:" + db,
                "",
                "")) {
            assertThat(runtime.isEnabled()).isTrue();
            assertThat(Files.exists(db)).isTrue();

            RuleDefinition rule = testRule();
            runtime.ruleStorage().addRule(rule);
            assertThat(runtime.ruleStorage().getRule(rule.getId())).isPresent();
        }
    }

    @Test
    void runtimeCreatesMissingSqliteParentDirectory() throws Exception {
        var db = Files.createTempDirectory("xa-mass-sqlite-runtime")
                .resolve("missing")
                .resolve("nested")
                .resolve("xa_mass.db");
        assertThat(Files.exists(db.getParent())).isFalse();

        try (JdbcStorageRuntime runtime = JdbcStorageRuntime.create(
                JdbcStorageMode.JDBC_SQLITE,
                "jdbc:sqlite:" + db,
                "",
                "")) {
            assertThat(runtime.isEnabled()).isTrue();
        }

        assertThat(Files.exists(db.getParent())).isTrue();
        assertThat(Files.exists(db)).isTrue();
    }

    private RuleDefinition testRule() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("sqlite_worker_check");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("isWorkerAvailable == true && isWorkerLocked == false");
        rule.setDescription("Worker must be available and unlocked");
        return rule;
    }
}
