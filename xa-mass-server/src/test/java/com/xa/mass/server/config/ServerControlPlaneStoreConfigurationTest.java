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
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerControlPlaneStoreConfigurationTest {

    @Test
    void devProfileCreatesOneExplicitBeanForEachServerControlPlaneStoreContract() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("dev");
            context.registerBean(JdbcStorageRuntime.class, () -> JdbcStorageRuntime.disabled());
            context.register(ServerControlPlaneStoreConfiguration.class);
            context.refresh();

            assertSingleBean(context, ApiKeyApplicationStore.class, InMemoryApiKeyApplicationStore.class);
            assertSingleBean(context, ApiKeyCredentialStore.class, InMemoryApiKeyCredentialStore.class);
            assertSingleBean(context, UserRolePermissionStore.class, InMemoryUserRolePermissionStore.class);
            assertSingleBean(context, OperatorCredentialStore.class, InMemoryOperatorCredentialStore.class);
            assertSingleBean(context, SubmitterViewerSessionStore.class, InMemorySubmitterViewerSessionStore.class);
            assertSingleBean(context, ApiUsageLedgerStore.class, InMemoryApiUsageLedgerStore.class);

            DefaultOperatorPrincipalDirectory directory = context.getBean(DefaultOperatorPrincipalDirectory.class);
            assertNotNull(directory.getPrincipal("ops-admin"));
        }
    }

    @Test
    void prodProfileFailsWhenJdbcRuntimeBeanIsMissing() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            context.register(ServerControlPlaneStoreConfiguration.class);

            RuntimeException error = assertThrows(RuntimeException.class, context::refresh);
            assertFailureContains(error, "JdbcStorageRuntime");
        }
    }

    @Test
    void prodProfileFailsWhenStorageRuntimeIsDisabled() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            context.registerBean(JdbcStorageRuntime.class, () -> JdbcStorageRuntime.disabled());
            context.register(ServerControlPlaneStoreConfiguration.class);

            RuntimeException error = assertThrows(RuntimeException.class, context::refresh);
            assertFailureContains(error, "prod requires mass.storage.mode to be JDBC-enabled");
        }
    }

    @Test
    void jdbcRuntimeCreatesJdbcStoresAndKeepsViewerSessionsMemoryOnly() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        try (JdbcStorageRuntime runtime = JdbcStorageRuntime.create(JdbcStorageMode.JDBC_H2, url, "sa", "");
             AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            context.registerBean(JdbcStorageRuntime.class, () -> runtime);
            context.register(ServerControlPlaneMigrationConfiguration.class);
            context.register(ServerControlPlaneStoreConfiguration.class);
            context.refresh();

            assertSingleBean(context, ApiKeyApplicationStore.class, JdbcApiKeyApplicationStore.class);
            assertSingleBean(context, ApiKeyCredentialStore.class, JdbcApiKeyCredentialStore.class);
            assertSingleBean(context, UserRolePermissionStore.class, JdbcUserRolePermissionStore.class);
            assertSingleBean(context, OperatorCredentialStore.class, JdbcOperatorCredentialStore.class);
            assertSingleBean(context, ApiUsageLedgerStore.class, JdbcApiUsageLedgerStore.class);
            assertSingleBean(context, SubmitterViewerSessionStore.class, InMemorySubmitterViewerSessionStore.class);

            DefaultOperatorPrincipalDirectory directory = context.getBean(DefaultOperatorPrincipalDirectory.class);
            assertNotNull(directory.getPrincipal("ops-admin"));
        }
    }

    @Test
    void prodProfileAllowsJdbcEnabledRuntime() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        try (JdbcStorageRuntime runtime = JdbcStorageRuntime.create(JdbcStorageMode.JDBC_H2, url, "sa", "");
             AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            context.registerBean(JdbcStorageRuntime.class, () -> runtime);
            context.register(ServerControlPlaneMigrationConfiguration.class);
            context.register(ServerControlPlaneStoreConfiguration.class);

            assertDoesNotThrow(context::refresh);
        }
    }

    private <T> void assertSingleBean(AnnotationConfigApplicationContext context,
                                      Class<T> contract,
                                      Class<? extends T> implementation) {
        String[] names = context.getBeanNamesForType(contract);
        assertEquals(1, names.length, contract.getSimpleName() + " bean count");
        assertInstanceOf(implementation, context.getBean(contract));
    }

    private void assertFailureContains(Throwable error, String expected) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected failure message to contain [" + expected + "] but was: " + error, error);
    }
}
