package com.xa.mass.api.worker.registration;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.server.config.ServerControlPlaneMigrationRunner;
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcWorkerRegistrationObservationStoreTest {

    @Test
    void h2PersistsRegistrationObservationsAcrossRestart() {
        try (StorageFixture fixture = h2Fixture()) {
            assertObservationRestart(fixture);
        }
    }

    @Test
    void sqlitePersistsRegistrationObservationsAcrossRestart() {
        try (StorageFixture fixture = sqliteFixture()) {
            assertObservationRestart(fixture);
        }
    }

    private void assertObservationRestart(StorageFixture fixture) {
        JdbcWorkerRegistrationObservationStore store =
                new JdbcWorkerRegistrationObservationStore(fixture.runtime().dataSource());
        WorkerRegistrationObservationService service = new WorkerRegistrationObservationService(store);
        PrincipalContext principal = PrincipalContext.internalService("worker-credential-1", "worker-agent");

        service.observeSuccessfulRegistration(
                "WORKER",
                "worker-001",
                "REGISTER",
                principal,
                Map.of(
                        "workerId", "worker-001",
                        "workerGroupId", "group-a",
                        "transportHint", "polling"
                )
        );

        JdbcWorkerRegistrationObservationStore restarted =
                new JdbcWorkerRegistrationObservationStore(fixture.runtime().dataSource());
        assertThat(restarted.listByResource("WORKER", "worker-001"))
                .hasSize(1)
                .first()
                .satisfies(record -> {
                    assertThat(record.action()).isEqualTo("REGISTER");
                    assertThat(record.principalId()).isEqualTo("worker-credential-1");
                    assertThat(record.principalType()).isEqualTo("SERVICE");
                    assertThat(record.requestHash()).hasSize(64);
                    assertThat(record.payloadJson()).contains("worker-001");
                    assertThat(record.occurredAt()).isBeforeOrEqualTo(Instant.now());
                });
    }

    private StorageFixture h2Fixture() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        JdbcStorageRuntime runtime = JdbcStorageRuntime.create(JdbcStorageMode.JDBC_H2, url, "sa", "");
        new ServerControlPlaneMigrationRunner(runtime).migrate();
        return new StorageFixture(runtime);
    }

    private StorageFixture sqliteFixture() {
        try {
            var db = Files.createTempDirectory("xa-mass-worker-observation-sqlite").resolve("xa_mass.db");
            JdbcStorageRuntime runtime = JdbcStorageRuntime.create(
                    JdbcStorageMode.JDBC_SQLITE,
                    "jdbc:sqlite:" + db,
                    "",
                    ""
            );
            new ServerControlPlaneMigrationRunner(runtime).migrate();
            return new StorageFixture(runtime);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite worker observation fixture", e);
        }
    }

    private record StorageFixture(JdbcStorageRuntime runtime) implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }
}
