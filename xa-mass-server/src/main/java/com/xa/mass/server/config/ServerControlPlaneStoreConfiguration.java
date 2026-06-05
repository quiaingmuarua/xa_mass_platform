package com.xa.mass.server.config;

import com.xa.mass.api.auth.DefaultOperatorPrincipalDirectory;
import com.xa.mass.api.auth.apikey.ApiKeyApplicationStore;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialStore;
import com.xa.mass.api.auth.apikey.InMemoryApiKeyApplicationStore;
import com.xa.mass.api.auth.apikey.InMemoryApiKeyCredentialStore;
import com.xa.mass.api.auth.apikey.JdbcApiKeyApplicationStore;
import com.xa.mass.api.auth.apikey.JdbcApiKeyCredentialStore;
import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import com.xa.mass.api.auth.iam.JdbcUserRolePermissionStore;
import com.xa.mass.api.auth.iam.UserRolePermissionStore;
import com.xa.mass.api.auth.operator.InMemoryOperatorCredentialStore;
import com.xa.mass.api.auth.operator.JdbcOperatorCredentialStore;
import com.xa.mass.api.auth.operator.OperatorCredentialStore;
import com.xa.mass.api.auth.session.InMemorySubmitterViewerSessionStore;
import com.xa.mass.api.auth.session.SubmitterViewerSessionStore;
import com.xa.mass.api.auth.usage.ApiUsageLedgerStore;
import com.xa.mass.api.auth.usage.InMemoryApiUsageLedgerStore;
import com.xa.mass.api.auth.usage.JdbcApiUsageLedgerStore;
import com.xa.mass.api.worker.registration.InMemoryWorkerRegistrationObservationStore;
import com.xa.mass.api.worker.registration.JdbcWorkerRegistrationObservationStore;
import com.xa.mass.api.worker.registration.WorkerRegistrationObservationService;
import com.xa.mass.api.worker.registration.WorkerRegistrationObservationStore;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@Configuration
@Profile({"dev", "prod"})
public class ServerControlPlaneStoreConfiguration {

    private final Environment environment;

    public ServerControlPlaneStoreConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public ApiKeyApplicationStore apiKeyApplicationStore(JdbcStorageRuntime jdbcStorageRuntime,
                                                         ObjectProvider<ServerControlPlaneMigrationRunner> migrationRunnerProvider) {
        if (jdbcStorageRuntime.isEnabled()) {
            migrationRunnerProvider.getObject();
            return new JdbcApiKeyApplicationStore(jdbcStorageRuntime.dataSource());
        }
        requireNonProdMemoryStore();
        return new InMemoryApiKeyApplicationStore();
    }

    @Bean
    public ApiKeyCredentialStore apiKeyCredentialStore(JdbcStorageRuntime jdbcStorageRuntime,
                                                       ObjectProvider<ServerControlPlaneMigrationRunner> migrationRunnerProvider) {
        if (jdbcStorageRuntime.isEnabled()) {
            migrationRunnerProvider.getObject();
            return new JdbcApiKeyCredentialStore(jdbcStorageRuntime.dataSource());
        }
        requireNonProdMemoryStore();
        return new InMemoryApiKeyCredentialStore();
    }

    @Bean
    public UserRolePermissionStore userRolePermissionStore(JdbcStorageRuntime jdbcStorageRuntime,
                                                           ObjectProvider<ServerControlPlaneMigrationRunner> migrationRunnerProvider) {
        if (jdbcStorageRuntime.isEnabled()) {
            migrationRunnerProvider.getObject();
            return new JdbcUserRolePermissionStore(jdbcStorageRuntime.dataSource());
        }
        requireNonProdMemoryStore();
        return InMemoryUserRolePermissionStore.bootstrapDefaults();
    }

    @Bean
    public DefaultOperatorPrincipalDirectory defaultOperatorPrincipalDirectory(UserRolePermissionStore store) {
        return new DefaultOperatorPrincipalDirectory(store);
    }

    @Bean
    public OperatorCredentialStore operatorCredentialStore(JdbcStorageRuntime jdbcStorageRuntime,
                                                           ObjectProvider<ServerControlPlaneMigrationRunner> migrationRunnerProvider) {
        if (jdbcStorageRuntime.isEnabled()) {
            migrationRunnerProvider.getObject();
            return new JdbcOperatorCredentialStore(jdbcStorageRuntime.dataSource());
        }
        requireNonProdMemoryStore();
        return new InMemoryOperatorCredentialStore();
    }

    @Bean
    public SubmitterViewerSessionStore submitterViewerSessionStore() {
        return new InMemorySubmitterViewerSessionStore();
    }

    @Bean
    public ApiUsageLedgerStore apiUsageLedgerStore(JdbcStorageRuntime jdbcStorageRuntime,
                                                   ObjectProvider<ServerControlPlaneMigrationRunner> migrationRunnerProvider) {
        if (jdbcStorageRuntime.isEnabled()) {
            migrationRunnerProvider.getObject();
            return new JdbcApiUsageLedgerStore(jdbcStorageRuntime.dataSource());
        }
        requireNonProdMemoryStore();
        return new InMemoryApiUsageLedgerStore();
    }

    @Bean
    public WorkerRegistrationObservationStore workerRegistrationObservationStore(
            JdbcStorageRuntime jdbcStorageRuntime,
            ObjectProvider<ServerControlPlaneMigrationRunner> migrationRunnerProvider) {
        if (jdbcStorageRuntime.isEnabled()) {
            migrationRunnerProvider.getObject();
            return new JdbcWorkerRegistrationObservationStore(jdbcStorageRuntime.dataSource());
        }
        requireNonProdMemoryStore();
        return new InMemoryWorkerRegistrationObservationStore();
    }

    @Bean
    public WorkerRegistrationObservationService workerRegistrationObservationService(
            WorkerRegistrationObservationStore store) {
        return new WorkerRegistrationObservationService(store);
    }

    private void requireNonProdMemoryStore() {
        if (isProdProfile()) {
            throw new IllegalStateException("prod requires mass.storage.mode to be JDBC-enabled");
        }
    }

    private boolean isProdProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        String[] effectiveProfiles = activeProfiles.length == 0 ? environment.getDefaultProfiles() : activeProfiles;
        return Arrays.asList(effectiveProfiles).contains("prod");
    }
}
