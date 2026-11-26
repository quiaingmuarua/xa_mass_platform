package com.xa.mass.server.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.server.assembly.controlplane.ControlPlaneDatabase;
import com.xa.mass.server.error.ServerException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteProjectDirectoryTest {
    @TempDir Path temp;

    @Test
    void bootstrapsHostProjectsOnceAndLoadsFromSqliteWithoutYamlOnRestart() throws Exception {
        Path dbPath = temp.resolve("control-plane.sqlite");
        var hostSeed = new ProjectAssemblyProperties(List.of(
                new ProjectDefinition("default", List.of(), null),
                new ProjectDefinition("agentforge", List.of("group-b", "group-a"), "custom-owner")
        ));
        var moduleProject = new ProjectDefinition("messages", List.of("msg-group"), null);

        try (var db = ControlPlaneDatabase.open(dbPath)) {
            var directory = new ProjectDirectory(hostSeed, List.of(moduleProject), db);
            assertThat(directory.projects()).containsOnlyKeys("default", "agentforge", "messages");
            assertThat(directory.require("default").workerGroupSource()).isEqualTo("registered");
            assertThat(directory.require("agentforge").workerGroupIds()).containsExactly("group-b", "group-a");
            assertThat(directory.exclusiveInputOwner("agentforge")).isEqualTo("custom-owner");
        }

        // Restart with empty YAML host list: SQLite retains host projects while module Beans stay live.
        var updatedModule = new ProjectDefinition("app-checks", List.of("check-group"), "app-checks");
        try (var db = ControlPlaneDatabase.open(dbPath)) {
            var restarted = new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of(updatedModule), db);
            assertThat(restarted.projects()).containsOnlyKeys("default", "agentforge", "app-checks");
            assertThat(restarted.require("agentforge").workerGroupIds()).containsExactly("group-b", "group-a");
            assertThat(restarted.exclusiveInputOwner("agentforge")).isEqualTo("custom-owner");
            assertThat(restarted.exclusiveInputOwner("app-checks")).isEqualTo("app-checks");
            // Module projects are never persisted into cp_projects.
            assertThat(new SqliteProjectDirectoryStore(db).loadAll())
                    .extracting(ProjectDefinition::projectId)
                    .containsExactly("default", "agentforge");
        }
    }

    @Test
    void emptyHostSeedWorksWithModuleProjectsAndAllowsLaterBootstrap() throws Exception {
        Path dbPath = temp.resolve("empty-host.sqlite");
        var moduleProject = new ProjectDefinition("messages", List.of("msg-group"), null);

        try (var db = ControlPlaneDatabase.open(dbPath)) {
            var directory = new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of(moduleProject), db);
            assertThat(directory.projects()).containsOnlyKeys("messages");
            assertThat(new SqliteProjectDirectoryStore(db).isEmpty()).isTrue();
        }

        // Later startup supplies host projects and bootstraps them into SQLite.
        try (var db = ControlPlaneDatabase.open(dbPath)) {
            var seeded = new ProjectDirectory(
                    new ProjectAssemblyProperties(List.of(new ProjectDefinition("default", List.of(), null))),
                    List.of(moduleProject),
                    db
            );
            assertThat(seeded.projects()).containsOnlyKeys("default", "messages");
            assertThat(new SqliteProjectDirectoryStore(db).loadAll())
                    .extracting(ProjectDefinition::projectId)
                    .containsExactly("default");
        }
    }

    @Test
    void conflictingHostSeedFailsBeforeWritingAndPersistedConflictFailsRestart() throws Exception {
        Path dbPath = temp.resolve("conflict.sqlite");
        var moduleMessages = new ProjectDefinition("messages", List.of("msg-group"), null);

        try (var db = ControlPlaneDatabase.open(dbPath)) {
            assertThatThrownBy(() -> new ProjectDirectory(
                    new ProjectAssemblyProperties(List.of(new ProjectDefinition("messages", List.of("other"), null))),
                    List.of(moduleMessages),
                    db
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate Project ID across startup declarations: messages");
            assertThat(new SqliteProjectDirectoryStore(db).isEmpty()).isTrue();

            // Persist "messages" while module is disabled.
            new ProjectDirectory(
                    new ProjectAssemblyProperties(List.of(new ProjectDefinition("messages", List.of("host-group"), null))),
                    List.of(),
                    db
            );
        }

        // Enabling a module with the same Project ID on restart fails loudly.
        try (var db = ControlPlaneDatabase.open(dbPath)) {
            assertThatThrownBy(() -> new ProjectDirectory(
                    new ProjectAssemblyProperties(List.of()),
                    List.of(moduleMessages),
                    db
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate Project ID across startup declarations: messages");
        }
    }

    @Test
    void queriesReadImmutableInMemoryDirectoryWithZeroSqliteIo() throws Exception {
        Path dbPath = temp.resolve("readonly-memory.sqlite");
        final ProjectDirectory directory;
        try (var db = ControlPlaneDatabase.open(dbPath)) {
            directory = new ProjectDirectory(
                    new ProjectAssemblyProperties(List.of(
                            new ProjectDefinition("default", List.of(), null),
                            new ProjectDefinition("ops", List.of("g1"), "ops-owner")
                    )),
                    List.of(),
                    db
            );
        }
        // Database is now closed; all runtime queries continue working from memory.
        assertThat(directory.projects()).containsOnlyKeys("default", "ops");
        directory.requireWorkerGroup("default", "any-group");
        directory.requireWorkerGroup("ops", "g1");
        directory.requireInputOwner("ops", "ops-owner");
        assertThatThrownBy(() -> directory.requireInputOwner("ops", "raw"))
                .isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> directory.requireWorkerGroup("ops", "g2"))
                .isInstanceOf(ServerException.class);
    }

    @Test
    void rejectsCorruptPersistedProjectRowsAtStartup() throws Exception {
        Path dbPath = temp.resolve("corrupt.sqlite");
        try (var db = ControlPlaneDatabase.open(dbPath)) {
            db.inTransaction(connection -> {
                try (var stmt = connection.createStatement()) {
                    stmt.execute("INSERT INTO cp_projects(project_id, ordinal, exclusive_input_owner) VALUES ('broken', 0, NULL)");
                }
                return null;
            });
            // Non-default project with empty workerGroupIds violates ProjectDefinition invariant.
            assertThatThrownBy(() -> new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of(), db))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Project must declare WorkerGroups");
        }
    }

    @Test
    void disabledControlPlaneKeepsYamlOnlyBehavior() {
        var disabled = ControlPlaneDatabase.disabled();
        var directory = new ProjectDirectory(
                new ProjectAssemblyProperties(List.of(new ProjectDefinition("yaml-only", List.of("g1"), null))),
                List.of(),
                disabled
        );
        assertThat(directory.projects()).containsOnlyKeys("yaml-only");
    }
}
