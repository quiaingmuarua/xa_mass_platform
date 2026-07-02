package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcStorageH2Test {

    @Test
    void ruleStoragePersistsRulesAsDefinitionStore() {
        try (StorageFixture fixture = h2Fixture()) {
            JdbcRuleStorage storage = new JdbcRuleStorage(fixture.dataSource(), new H2JdbcDialect());
            RuleDefinition rule = testRule();
            storage.addRule(rule);

            assertThat(storage.getRule(rule.getId())).isPresent();
            assertThat(storage.getRulesByType(rule.getType())).hasSize(1);
            assertThat(storage.deleteRule(rule.getId())).isTrue();
            assertThat(storage.getAllRules()).isEmpty();
        }
    }

    private StorageFixture h2Fixture() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        config.setUsername("sa");
        config.setPassword("");
        HikariDataSource dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/control-plane").load().migrate();
        return new StorageFixture(dataSource);
    }

    private RuleDefinition testRule() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("basic_worker_check");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("isWorkerAvailable == true && isWorkerLocked == false");
        rule.setDescription("Worker must be available and unlocked");
        return rule;
    }

    private record StorageFixture(HikariDataSource dataSource) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
