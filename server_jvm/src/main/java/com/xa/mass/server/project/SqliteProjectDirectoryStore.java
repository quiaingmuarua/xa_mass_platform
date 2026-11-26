package com.xa.mass.server.project;

import com.xa.mass.server.assembly.controlplane.ControlPlaneDatabase;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Package-local SQLite persistence adapter for host-declared Projects in {@link ProjectDirectory}.
 *
 * <p>Used strictly at startup to load existing host Project topology or bootstrap it once from
 * {@code xa.mass.project-assembly.projects}. Runtime queries are served from {@link ProjectDirectory}'s
 * immutable in-memory map with zero SQLite I/O.
 */
class SqliteProjectDirectoryStore {
    private final ControlPlaneDatabase database;

    SqliteProjectDirectoryStore(ControlPlaneDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    boolean isEmpty() throws IOException {
        return run("check control-plane Project directory state", () -> database.inTransaction(connection -> {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT EXISTS(SELECT 1 FROM cp_projects) OR EXISTS(SELECT 1 FROM cp_project_worker_groups)")) {
                rows.next();
                return rows.getInt(1) == 0;
            }
        }));
    }

    List<ProjectDefinition> loadAll() throws IOException {
        return run("load control-plane Project directory", () -> database.inTransaction(connection -> {
            var groupsByProject = new LinkedHashMap<String, List<String>>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT project_id, worker_group_id FROM cp_project_worker_groups ORDER BY project_id ASC, ordinal ASC")) {
                while (rows.next()) {
                    groupsByProject.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>())
                            .add(rows.getString(2));
                }
            }
            var projects = new ArrayList<ProjectDefinition>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT project_id, exclusive_input_owner FROM cp_projects ORDER BY ordinal ASC")) {
                while (rows.next()) {
                    String projectId = rows.getString(1);
                    String exclusiveInputOwner = rows.getString(2);
                    projects.add(new ProjectDefinition(
                            projectId,
                            List.copyOf(groupsByProject.getOrDefault(projectId, List.of())),
                            exclusiveInputOwner
                    ));
                }
            }
            return List.copyOf(projects);
        }));
    }

    void bootstrap(List<ProjectDefinition> projects) throws IOException {
        Objects.requireNonNull(projects, "projects");
        run("bootstrap control-plane Project directory", () -> database.inTransaction(connection -> {
            try (var check = connection.createStatement();
                 var existing = check.executeQuery(
                         "SELECT EXISTS(SELECT 1 FROM cp_projects) OR EXISTS(SELECT 1 FROM cp_project_worker_groups)")) {
                existing.next();
                if (existing.getInt(1) != 0) {
                    throw new SQLException("Control-plane Project directory is already populated");
                }
            }
            try (var insertProject = connection.prepareStatement("""
                    INSERT INTO cp_projects(project_id, ordinal, exclusive_input_owner)
                    VALUES (?, ?, ?)
                    """);
                 var insertGroup = connection.prepareStatement("""
                    INSERT INTO cp_project_worker_groups(project_id, ordinal, worker_group_id)
                    VALUES (?, ?, ?)
                    """)) {
                int projectOrdinal = 0;
                for (var project : projects) {
                    insertProject.setString(1, project.projectId());
                    insertProject.setInt(2, projectOrdinal++);
                    insertProject.setString(3, project.exclusiveInputOwner());
                    insertProject.executeUpdate();
                    insertWorkerGroups(insertGroup, project.projectId(), project.workerGroupIds());
                }
            }
            return null;
        }));
    }

    private static void insertWorkerGroups(PreparedStatement statement, String projectId, List<String> workerGroupIds)
            throws SQLException {
        int ordinal = 0;
        for (String groupId : workerGroupIds) {
            statement.setString(1, projectId);
            statement.setInt(2, ordinal++);
            statement.setString(3, groupId);
            statement.executeUpdate();
        }
    }

    private static <T> T run(String action, SqlCall<T> call) throws IOException {
        try {
            return call.execute();
        } catch (SQLException failure) {
            throw new IOException("Failed to " + action, failure);
        }
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T execute() throws SQLException;
    }
}
