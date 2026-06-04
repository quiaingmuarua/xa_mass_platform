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
import com.xa.mass.api.auth.session.InMemorySubmitterViewerSessionStore;
import com.xa.mass.api.auth.session.SubmitterViewerSessionStore;
import com.xa.mass.api.auth.usage.ApiUsageLedgerStore;
import com.xa.mass.api.auth.usage.InMemoryApiUsageLedgerStore;
import com.xa.mass.api.auth.usage.JdbcApiUsageLedgerStore;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"dev", "prod"})
public class ServerControlPlaneStoreConfiguration {

    @Bean
    public ApiKeyApplicationStore apiKeyApplicationStore(ObjectProvider<JdbcStorageRuntime> jdbcStorageRuntimeProvider,
                                                         ObjectProvider<ServerControlPlaneMigrationRunner> migrationRunnerProvider) {
        JdbcStorageRuntime jdbcStorageRuntime = jdbcStorageRuntimeProvider.getIfAvailable(JdbcStorageRuntime::disabled);
        if (jdbcStorageRuntime.isEnabled()) {
            migrationRunnerProvider.getObject();
            return new JdbcApiKeyApplicationStore(jdbcStorageRuntime.dataSource());
        }
        return new InMemoryApiKeyApplicationStore();
    }

    @Bean
    public ApiKeyCredentialStore apiKeyCredentialStore(ObjectProvider<JdbcStorageRuntime> jdbcStorageRuntimeProvider,
                                                       ObjectProvider<ServerControlPlaneMigrationRunner> migrationRunnerProvider) {
        JdbcStorageRuntime jdbcStorageRuntime = jdbcStorageRuntimeProvider.getIfAvailable(JdbcStorageRuntime::disabled);
        if (jdbcStorageRuntime.isEnabled()) {
            migrationRunnerProvider.getObject();
            return new JdbcApiKeyCredentialStore(jdbcStorageRuntime.dataSource());
        }
        return new InMemoryApiKeyCredentialStore();
    }

    @Bean
    public UserRolePermissionStore userRolePermissionStore(ObjectProvider<JdbcStorageRuntime> jdbcStorageRuntimeProvider,
                                                           ObjectProvider<ServerControlPlaneMigrationRunner> migrationRunnerProvider) {
        JdbcStorageRuntime jdbcStorageRuntime = jdbcStorageRuntimeProvider.getIfAvailable(JdbcStorageRuntime::disabled);
        if (jdbcStorageRuntime.isEnabled()) {
            migrationRunnerProvider.getObject();
            return new JdbcUserRolePermissionStore(jdbcStorageRuntime.dataSource());
        }
        return InMemoryUserRolePermissionStore.bootstrapDefaults();
    }

    @Bean
    public DefaultOperatorPrincipalDirectory defaultOperatorPrincipalDirectory(UserRolePermissionStore store) {
        return new DefaultOperatorPrincipalDirectory(store);
    }

    @Bean
    public SubmitterViewerSessionStore submitterViewerSessionStore() {
        return new InMemorySubmitterViewerSessionStore();
    }

    @Bean
    public ApiUsageLedgerStore apiUsageLedgerStore(ObjectProvider<JdbcStorageRuntime> jdbcStorageRuntimeProvider,
                                                   ObjectProvider<ServerControlPlaneMigrationRunner> migrationRunnerProvider) {
        JdbcStorageRuntime jdbcStorageRuntime = jdbcStorageRuntimeProvider.getIfAvailable(JdbcStorageRuntime::disabled);
        if (jdbcStorageRuntime.isEnabled()) {
            migrationRunnerProvider.getObject();
            return new JdbcApiUsageLedgerStore(jdbcStorageRuntime.dataSource());
        }
        return new InMemoryApiUsageLedgerStore();
    }
}
