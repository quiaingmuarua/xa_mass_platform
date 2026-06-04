package com.xa.mass.api.auth.apikey;

import com.xa.mass.api.auth.ApiPermissionNames;
import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import com.xa.mass.sdk.auth.CredentialAuthProjectionWriter;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import com.xa.mass.server.auth.jdbc.JdbcSubmitterRegistry;
import com.xa.mass.server.config.ServerControlPlaneMigrationRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcApiKeyLifecycleStoreTest {

    @Test
    void h2PersistsApiKeyLifecycleAndProjectionAcrossRestart() {
        try (StorageFixture fixture = h2Fixture()) {
            assertApiKeyLifecycleAndProjectionRestart(fixture);
        }
    }

    @Test
    void sqlitePersistsApiKeyLifecycleAndProjectionAcrossRestart() {
        try (StorageFixture fixture = sqliteFixture()) {
            assertApiKeyLifecycleAndProjectionRestart(fixture);
        }
    }

    @Test
    void projectionFailureRevokesCreatedLifecycleRecord() {
        InMemoryUserRolePermissionStore userStore = InMemoryUserRolePermissionStore.bootstrapDefaults();
        InMemoryApiKeyCredentialStore credentialStore = new InMemoryApiKeyCredentialStore();
        ApiKeyCredentialService service = new ApiKeyCredentialService(
                new InMemoryApiKeyApplicationStore(),
                credentialStore,
                userStore,
                new FailingProjectionWriter()
        );

        assertThatThrownBy(() -> service.createOperatorKey(command("projection-failure-key")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projection unavailable");

        ApiKeyCredentialRecord record = credentialStore.getByPrincipalId("projection-failure-key");
        assertThat(record).isNotNull();
        assertThat(record.status()).isEqualTo(ApiKeyCredentialStatus.REVOKED);
        assertThat(record.revokeReason()).contains("auth projection failed");
    }

    private void assertApiKeyLifecycleAndProjectionRestart(StorageFixture fixture) {
        JdbcApiKeyApplicationStore applicationStore = new JdbcApiKeyApplicationStore(fixture.runtime().dataSource());
        ApiKeyApplicationRecord application = new ApiKeyApplicationRecord(
                "app-" + UUID.randomUUID(),
                "ops-admin",
                "Ops Admin",
                "crawler-api-key",
                "ops-admin",
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                List.of(ApiPermissionNames.TASK_CREATE, ApiPermissionNames.TASK_VIEW),
                "crawler worker",
                ApiKeyApplicationStatus.PENDING,
                null,
                null,
                Instant.now(),
                null,
                Map.of("source", "test")
        );
        applicationStore.create(application);
        assertThat(applicationStore.markApproved(application.applicationId(), "ops-admin", "approved").status())
                .isEqualTo(ApiKeyApplicationStatus.APPROVED);

        JdbcSubmitterRegistry registry = new JdbcSubmitterRegistry(fixture.runtime().dataSource(), fixture.mode());
        ApiKeyCredentialService service = new ApiKeyCredentialService(
                applicationStore,
                new JdbcApiKeyCredentialStore(fixture.runtime().dataSource()),
                InMemoryUserRolePermissionStore.bootstrapDefaults(),
                registry
        );

        ApiKeyCredentialService.CreatedApiKey created = service.createOperatorKey(command("crawler-api-key"));
        assertThat(registry.authenticate(created.rawSecret())).isNotNull();

        JdbcSubmitterRegistry restartedRegistry = new JdbcSubmitterRegistry(fixture.runtime().dataSource(), fixture.mode());
        ApiKeyCredentialService restartedService = new ApiKeyCredentialService(
                new JdbcApiKeyApplicationStore(fixture.runtime().dataSource()),
                new JdbcApiKeyCredentialStore(fixture.runtime().dataSource()),
                InMemoryUserRolePermissionStore.bootstrapDefaults(),
                restartedRegistry
        );
        PrincipalContext restartedPrincipal = restartedRegistry.authenticate(created.rawSecret());
        assertThat(restartedPrincipal).isNotNull();
        assertThat(restartedService.validateAuthenticatedPrincipal(restartedPrincipal)).isNotNull();
        assertThat(restartedService.list()).singleElement()
                .extracting(ApiKeyCredentialRecord::keyId)
                .isEqualTo(created.record().keyId());

        restartedService.revoke(created.record().keyId(), "ops-admin", "rotation");
        assertThat(restartedRegistry.authenticate(created.rawSecret())).isNull();

        JdbcSubmitterRegistry revokedRestartedRegistry =
                new JdbcSubmitterRegistry(fixture.runtime().dataSource(), fixture.mode());
        ApiKeyCredentialService revokedRestartedService = new ApiKeyCredentialService(
                new JdbcApiKeyApplicationStore(fixture.runtime().dataSource()),
                new JdbcApiKeyCredentialStore(fixture.runtime().dataSource()),
                InMemoryUserRolePermissionStore.bootstrapDefaults(),
                revokedRestartedRegistry
        );
        assertThat(revokedRestartedRegistry.authenticate(created.rawSecret())).isNull();
        assertThat(revokedRestartedService.get(created.record().keyId()).status())
                .isEqualTo(ApiKeyCredentialStatus.REVOKED);
    }

    private ApiKeyCredentialService.CreateApiKeyCommand command(String principalId) {
        return new ApiKeyCredentialService.CreateApiKeyCommand(
                principalId,
                "ops-admin",
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                List.of(ApiPermissionNames.TASK_CREATE, ApiPermissionNames.TASK_VIEW),
                "ops-admin",
                Instant.now().plusSeconds(3600),
                Map.of("lane", "crawler"),
                null
        );
    }

    private StorageFixture h2Fixture() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        JdbcStorageRuntime runtime = JdbcStorageRuntime.create(JdbcStorageMode.JDBC_H2, url, "sa", "");
        new ServerControlPlaneMigrationRunner(runtime).migrate();
        return new StorageFixture(runtime, JdbcStorageMode.JDBC_H2);
    }

    private StorageFixture sqliteFixture() {
        try {
            var db = Files.createTempDirectory("xa-mass-api-key-sqlite").resolve("xa_mass.db");
            JdbcStorageRuntime runtime = JdbcStorageRuntime.create(
                    JdbcStorageMode.JDBC_SQLITE,
                    "jdbc:sqlite:" + db,
                    "",
                    ""
            );
            new ServerControlPlaneMigrationRunner(runtime).migrate();
            return new StorageFixture(runtime, JdbcStorageMode.JDBC_SQLITE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite API-key fixture", e);
        }
    }

    private record StorageFixture(JdbcStorageRuntime runtime, JdbcStorageMode mode) implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }

    private static final class FailingProjectionWriter implements CredentialAuthProjectionWriter {
        @Override
        public void projectCredential(com.xa.mass.sdk.auth.SubmitterRegistration submitterRegistration) {
            throw new IllegalStateException("projection unavailable");
        }

        @Override
        public boolean hasProjectedCredential(String principalId) {
            return false;
        }
    }
}
