package com.xa.mass.server.auth.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSubmitterRegistryTest {

    @Test
    void persistsPrincipalTruthAndRestoresAuthentication() {
        try (StorageFixture fixture = h2Fixture()) {
            JdbcSubmitterRegistry registry = new JdbcSubmitterRegistry(fixture.dataSource(), JdbcStorageMode.JDBC_H2);
            registry.register(SubmitterRegistration.builder()
                    .principalId("crawler-submitter")
                    .credential("crawler-submit-secret")
                    .userId("crawler-user")
                    .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                    .projectScopes(List.of("crawlerApp"))
                    .eventScopes(List.of("crawler.fetch-page"))
                    .attributes(Map.of("lane", "crawler"))
                    .build());

            PrincipalContext authenticated = registry.authenticate("crawler-submit-secret");
            assertThat(authenticated).isNotNull();
            assertThat(authenticated.getPrincipalId()).isEqualTo("crawler-submitter");
            assertThat(registry.getSubmitter("crawler-submitter")).isNotNull();
            assertThat(registry.listSubmitters()).hasSize(1);

            JdbcSubmitterRegistry restartedRegistry = new JdbcSubmitterRegistry(fixture.dataSource(), JdbcStorageMode.JDBC_H2);
            PrincipalContext restartedPrincipal = restartedRegistry.authenticate("crawler-submit-secret");
            assertThat(restartedPrincipal).isNotNull();
            assertThat(restartedPrincipal.getPrincipalId()).isEqualTo("crawler-submitter");
            assertThat(restartedRegistry.getSubmitter("crawler-submitter")).isNotNull();
            assertThat(restartedRegistry.listSubmitters()).hasSize(1);
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

    private record StorageFixture(HikariDataSource dataSource) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
