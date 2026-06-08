package com.xa.mass.api.auth.iam;

import com.xa.mass.api.auth.ApiPermissionNames;
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import com.xa.mass.server.config.ServerControlPlaneMigrationRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcUserRolePermissionStoreTest {

    @Test
    void h2PersistsUsersRolesAndBindingsAcrossRestart() {
        try (StorageFixture fixture = h2Fixture()) {
            assertUserRolePermissionRestart(fixture);
        }
    }

    @Test
    void sqlitePersistsUsersRolesAndBindingsAcrossRestart() {
        try (StorageFixture fixture = sqliteFixture()) {
            assertUserRolePermissionRestart(fixture);
        }
    }

    @Test
    void bootstrapDoesNotOverwriteExistingOperatorData() {
        try (StorageFixture fixture = h2Fixture()) {
            JdbcUserRolePermissionStore store = new JdbcUserRolePermissionStore(fixture.runtime().dataSource());
            UserRecord disabledViewer = new UserRecord(
                    "ops-viewer",
                    "Local Viewer",
                    "local-viewer@example.internal",
                    UserStatus.DISABLED,
                    Map.of("source", "local"),
                    Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(20)
            );
            assertThat(store.updateUser(disabledViewer)).isNotNull();

            JdbcUserRolePermissionStore restarted = new JdbcUserRolePermissionStore(fixture.runtime().dataSource());
            assertThat(restarted.getUser("ops-viewer").displayName()).isEqualTo("Local Viewer");
            assertThat(restarted.getUser("ops-viewer").status()).isEqualTo(UserStatus.DISABLED);
            assertThat(restarted.listRoleBindings("ops-viewer"))
                    .extracting(UserRoleBindingRecord::roleId)
                    .contains("OPS_VIEWER");
        }
    }

    @Test
    void bootstrapConvergesExistingSystemRolesToCurrentDefaultPermissions() {
        try (StorageFixture fixture = h2Fixture()) {
            JdbcUserRolePermissionStore oldStore =
                    new JdbcUserRolePermissionStore(fixture.runtime().dataSource(), false);
            oldStore.createRole(new RoleRecord(
                    "OPS_ADMIN",
                    "Ops Admin",
                    "Old admin role",
                    Set.of(ApiPermissionNames.TASK_VIEW),
                    true,
                    Instant.EPOCH
            ));

            JdbcUserRolePermissionStore restarted =
                    new JdbcUserRolePermissionStore(fixture.runtime().dataSource());

            RoleRecord admin = restarted.getRole("OPS_ADMIN");
            assertThat(admin.description()).isEqualTo("Full server operator access");
            assertThat(admin.permissions())
                    .contains(
                            ApiPermissionNames.API_KEY_VIEW,
                            ApiPermissionNames.API_KEY_APPROVE,
                            ApiPermissionNames.API_KEY_REVOKE,
                            ApiPermissionNames.API_USAGE_VIEW
                    );
            assertThat(admin.permissions()).containsExactlyInAnyOrderElementsOf(ApiPermissionNames.ALL);
        }
    }

    @Test
    void bootstrapDoesNotConvergeCustomRoleWithDefaultRoleId() {
        try (StorageFixture fixture = h2Fixture()) {
            JdbcUserRolePermissionStore oldStore =
                    new JdbcUserRolePermissionStore(fixture.runtime().dataSource(), false);
            oldStore.createRole(new RoleRecord(
                    "OPS_ADMIN",
                    "Local Admin",
                    "Custom local role",
                    Set.of(ApiPermissionNames.TASK_VIEW),
                    false,
                    Instant.EPOCH
            ));

            JdbcUserRolePermissionStore restarted =
                    new JdbcUserRolePermissionStore(fixture.runtime().dataSource());

            RoleRecord admin = restarted.getRole("OPS_ADMIN");
            assertThat(admin.name()).isEqualTo("Local Admin");
            assertThat(admin.description()).isEqualTo("Custom local role");
            assertThat(admin.permissions()).containsExactly(ApiPermissionNames.TASK_VIEW);
            assertThat(admin.systemRole()).isFalse();
        }
    }

    private void assertUserRolePermissionRestart(StorageFixture fixture) {
        JdbcUserRolePermissionStore store = new JdbcUserRolePermissionStore(fixture.runtime().dataSource());
        assertThat(store.getUser("ops-admin")).isNotNull();
        assertThat(store.getRole("OPS_ADMIN")).isNotNull();
        assertThat(store.listPermissionNames()).contains(ApiPermissionNames.API_KEY_APPROVE);

        UserRecord user = new UserRecord(
                "task-operator",
                "Task Operator",
                "task-operator@example.internal",
                UserStatus.ACTIVE,
                Map.of("team", "runtime"),
                Instant.now(),
                Instant.now()
        );
        store.createUser(user);
        store.createRole(new RoleRecord(
                "TASK_OPERATOR",
                "Task Operator",
                "Can create and view tasks",
                Set.of(ApiPermissionNames.TASK_CREATE, ApiPermissionNames.TASK_VIEW),
                false,
                Instant.now()
        ));
        store.bindRole(new UserRoleBindingRecord("task-operator", "TASK_OPERATOR", "ops-admin", Instant.now()));

        JdbcUserRolePermissionStore restarted = new JdbcUserRolePermissionStore(fixture.runtime().dataSource());
        assertThat(restarted.getUser("task-operator").attributes()).containsEntry("team", "runtime");
        assertThat(restarted.getRole("TASK_OPERATOR").permissions()).contains(ApiPermissionNames.TASK_CREATE);
        assertThat(restarted.listRoleBindings("task-operator"))
                .singleElement()
                .extracting(UserRoleBindingRecord::roleId)
                .isEqualTo("TASK_OPERATOR");

        assertThat(restarted.unbindRole("task-operator", "TASK_OPERATOR")).isTrue();
        JdbcUserRolePermissionStore afterUnbindRestart =
                new JdbcUserRolePermissionStore(fixture.runtime().dataSource());
        assertThat(afterUnbindRestart.listRoleBindings("task-operator")).isEmpty();
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
            var db = Files.createTempDirectory("xa-mass-iam-sqlite").resolve("xa_mass.db");
            JdbcStorageRuntime runtime = JdbcStorageRuntime.create(
                    JdbcStorageMode.JDBC_SQLITE,
                    "jdbc:sqlite:" + db,
                    "",
                    ""
            );
            new ServerControlPlaneMigrationRunner(runtime).migrate();
            return new StorageFixture(runtime);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite IAM fixture", e);
        }
    }

    private record StorageFixture(JdbcStorageRuntime runtime) implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }
}
