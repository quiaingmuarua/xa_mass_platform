package com.xa.mass.api.auth.operator;

import com.xa.mass.server.config.ServerControlPlaneMigrationRunner;
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorCredentialStoreTest {

    @Test
    void memoryStoreUpsertsAndListsCredentials() {
        assertStore(new InMemoryOperatorCredentialStore());
    }

    @Test
    void h2PersistsCredentialsAcrossRestart() {
        try (StorageFixture fixture = h2Fixture()) {
            assertJdbcRestart(fixture);
        }
    }

    @Test
    void sqlitePersistsCredentialsAcrossRestart() {
        try (StorageFixture fixture = sqliteFixture()) {
            assertJdbcRestart(fixture);
        }
    }

    private void assertStore(OperatorCredentialStore store) {
        OperatorCredentialRecord created = credential("ops-admin", "hash-1", OperatorCredentialStatus.ACTIVE);
        store.upsert(created);
        assertThat(store.get("ops-admin").passwordHash()).isEqualTo("hash-1");
        assertThat(store.hasActiveCredential()).isTrue();

        store.upsert(credential("ops-admin", "hash-2", OperatorCredentialStatus.DISABLED));
        assertThat(store.get("ops-admin").passwordHash()).isEqualTo("hash-2");
        assertThat(store.hasActiveCredential()).isFalse();
        assertThat(store.list()).singleElement().extracting(OperatorCredentialRecord::userId).isEqualTo("ops-admin");
    }

    private void assertJdbcRestart(StorageFixture fixture) {
        JdbcOperatorCredentialStore store = new JdbcOperatorCredentialStore(fixture.runtime().dataSource());
        store.upsert(credential("ops-admin", "hash-1", OperatorCredentialStatus.ACTIVE));

        JdbcOperatorCredentialStore restarted = new JdbcOperatorCredentialStore(fixture.runtime().dataSource());
        assertThat(restarted.get("ops-admin").passwordHash()).isEqualTo("hash-1");
        assertThat(restarted.hasActiveCredential()).isTrue();

        restarted.upsert(credential("ops-admin", "hash-2", OperatorCredentialStatus.DISABLED));
        JdbcOperatorCredentialStore afterUpdate = new JdbcOperatorCredentialStore(fixture.runtime().dataSource());
        assertThat(afterUpdate.get("ops-admin").status()).isEqualTo(OperatorCredentialStatus.DISABLED);
        assertThat(afterUpdate.hasActiveCredential()).isFalse();
    }

    private OperatorCredentialRecord credential(String userId,
                                                String hash,
                                                OperatorCredentialStatus status) {
        Instant now = Instant.now();
        return new OperatorCredentialRecord(userId, hash, "test", status, now, now);
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
            var db = Files.createTempDirectory("xa-mass-operator-credential-sqlite").resolve("xa_mass.db");
            JdbcStorageRuntime runtime = JdbcStorageRuntime.create(
                    JdbcStorageMode.JDBC_SQLITE,
                    "jdbc:sqlite:" + db,
                    "",
                    ""
            );
            new ServerControlPlaneMigrationRunner(runtime).migrate();
            return new StorageFixture(runtime);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite operator credential fixture", e);
        }
    }

    private record StorageFixture(JdbcStorageRuntime runtime) implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }
}
