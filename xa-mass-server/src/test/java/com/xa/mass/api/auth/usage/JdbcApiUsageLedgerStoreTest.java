package com.xa.mass.api.auth.usage;

import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import com.xa.mass.server.config.ServerControlPlaneMigrationRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcApiUsageLedgerStoreTest {

    @Test
    void h2PersistsUsageRecordsAcrossRestart() {
        try (StorageFixture fixture = h2Fixture()) {
            assertUsageLedgerRestart(fixture);
        }
    }

    @Test
    void sqlitePersistsUsageRecordsAcrossRestart() {
        try (StorageFixture fixture = sqliteFixture()) {
            assertUsageLedgerRestart(fixture);
        }
    }

    private void assertUsageLedgerRestart(StorageFixture fixture) {
        JdbcApiUsageLedgerStore store = new JdbcApiUsageLedgerStore(fixture.runtime().dataSource());
        ApiUsageLedgerService service = new ApiUsageLedgerService(store);
        PrincipalContext principal = new PrincipalContext(
                "crawler-key",
                "ops-admin",
                "crawlerApp",
                List.of("task:create", "task:view"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of(ApiKeyCredentialService.ATTR_KEY_ID, "ak-usage-jdbc")
        );

        ApiUsageLedgerRecord accepted = service.recordAccepted(
                principal,
                ApiUsageOperation.TASK_CREATE,
                "crawlerApp",
                null,
                "task-001",
                null,
                "req-create",
                2
        );
        service.recordRejected(
                principal,
                ApiUsageOperation.TASK_RESULT_READ,
                "crawlerApp",
                null,
                "task-002",
                null,
                "req-read"
        );
        service.recordFailedAfterAccept(
                principal,
                ApiUsageOperation.TASK_ITEM_SYNC_APPEND,
                "crawlerApp",
                "crawler.fetch-page",
                "task-003",
                "msg-003",
                "req-sync",
                "IllegalStateException: bridge failed",
                400
        );

        assertThat(store.append(accepted).usageId()).isEqualTo(accepted.usageId());

        JdbcApiUsageLedgerStore restarted = new JdbcApiUsageLedgerStore(fixture.runtime().dataSource());
        assertThat(restarted.listByKeyId("ak-usage-jdbc"))
                .extracting(ApiUsageLedgerRecord::status)
                .containsExactlyInAnyOrder(
                        ApiUsageStatus.ACCEPTED,
                        ApiUsageStatus.REJECTED,
                        ApiUsageStatus.FAILED_AFTER_ACCEPT
                );
        assertThat(restarted.listByPrincipalId("crawler-key"))
                .hasSize(3)
                .anySatisfy(record -> {
                    assertThat(record.operation()).isEqualTo(ApiUsageOperation.TASK_ITEM_SYNC_APPEND);
                    assertThat(record.failureReason()).isEqualTo("IllegalStateException: bridge failed");
                    assertThat(record.failureStatus()).isEqualTo(400);
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
            var db = Files.createTempDirectory("xa-mass-usage-sqlite").resolve("xa_mass.db");
            JdbcStorageRuntime runtime = JdbcStorageRuntime.create(
                    JdbcStorageMode.JDBC_SQLITE,
                    "jdbc:sqlite:" + db,
                    "",
                    ""
            );
            new ServerControlPlaneMigrationRunner(runtime).migrate();
            return new StorageFixture(runtime);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite usage fixture", e);
        }
    }

    private record StorageFixture(JdbcStorageRuntime runtime) implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }
}
