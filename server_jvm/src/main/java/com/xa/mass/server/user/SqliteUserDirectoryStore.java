package com.xa.mass.server.user;

import com.xa.mass.server.assembly.controlplane.ControlPlaneDatabase;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Package-local SQLite persistence adapter for {@link UserDirectory}.
 *
 * <p>Used only during startup load/bootstrap and infrequent admin user mutations. Request authentication
 * and directory reads are served from {@link UserDirectory}'s immutable in-memory snapshot without SQLite I/O.
 */
class SqliteUserDirectoryStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ControlPlaneDatabase database;
    private final ObjectMapper mapper;

    SqliteUserDirectoryStore(ControlPlaneDatabase database, ObjectMapper mapper) {
        this.database = Objects.requireNonNull(database, "database");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    boolean isEmpty() throws IOException {
        return run("check control-plane user directory state", () -> database.inTransaction(connection -> {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT EXISTS(SELECT 1 FROM cp_roles) OR EXISTS(SELECT 1 FROM cp_users)")) {
                rows.next();
                return rows.getInt(1) == 0;
            }
        }));
    }

    UserDirectory.Seed loadAll() throws IOException {
        return run("load control-plane user directory", () -> database.inTransaction(connection -> {
            var roles = new ArrayList<UserDirectory.Role>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT role_id, label, permissions_json, task_scope, project_scope, home FROM cp_roles ORDER BY ordinal ASC")) {
                while (rows.next()) {
                    List<String> permissions = mapper.readValue(rows.getString(3), STRING_LIST);
                    roles.add(new UserDirectory.Role(
                            rows.getString(1),
                            rows.getString(2),
                            permissions,
                            rows.getString(4),
                            rows.getString(5),
                            rows.getString(6)
                    ));
                }
            }
            var projectsByUser = new LinkedHashMap<String, List<String>>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT user_id, project_id FROM cp_user_projects ORDER BY user_id ASC, ordinal ASC")) {
                while (rows.next()) {
                    projectsByUser.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>())
                            .add(rows.getString(2));
                }
            }
            var users = new ArrayList<UserDirectory.SeedUser>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT user_id, display_name, role_id, access_key_sha256 FROM cp_users ORDER BY ordinal ASC")) {
                while (rows.next()) {
                    String userId = rows.getString(1);
                    users.add(new UserDirectory.SeedUser(
                            userId,
                            rows.getString(2),
                            rows.getString(3),
                            List.copyOf(projectsByUser.getOrDefault(userId, List.of())),
                            rows.getString(4)
                    ));
                }
            }
            return new UserDirectory.Seed(List.copyOf(roles), List.copyOf(users));
        }));
    }

    void bootstrap(UserDirectory.Seed seed) throws IOException {
        Objects.requireNonNull(seed, "seed");
        run("bootstrap control-plane user directory", () -> database.inTransaction(connection -> {
            try (var check = connection.createStatement();
                 var existing = check.executeQuery(
                         "SELECT EXISTS(SELECT 1 FROM cp_roles) OR EXISTS(SELECT 1 FROM cp_users)")) {
                existing.next();
                if (existing.getInt(1) != 0) {
                    throw new SQLException("Control-plane user directory is already populated");
                }
            }
            try (var insertRole = connection.prepareStatement("""
                    INSERT INTO cp_roles(role_id, ordinal, label, permissions_json, task_scope, project_scope, home)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                int ordinal = 0;
                for (var role : seed.roles()) {
                    insertRole.setString(1, role.id());
                    insertRole.setInt(2, ordinal++);
                    insertRole.setString(3, role.label());
                    insertRole.setString(4, mapper.writeValueAsString(role.permissions()));
                    insertRole.setString(5, role.taskScope());
                    insertRole.setString(6, role.projectScope());
                    insertRole.setString(7, role.home());
                    insertRole.executeUpdate();
                }
            }
            try (var insertUser = connection.prepareStatement("""
                    INSERT INTO cp_users(user_id, ordinal, display_name, role_id, access_key_sha256)
                    VALUES (?, ?, ?, ?, ?)
                    """);
                 var insertProject = connection.prepareStatement("""
                    INSERT INTO cp_user_projects(user_id, ordinal, project_id)
                    VALUES (?, ?, ?)
                    """)) {
                int userOrdinal = 0;
                for (var user : seed.users()) {
                    insertUser.setString(1, user.userId());
                    insertUser.setInt(2, userOrdinal++);
                    insertUser.setString(3, user.displayName());
                    insertUser.setString(4, user.role());
                    insertUser.setString(5, user.accessKeySha256());
                    insertUser.executeUpdate();
                    insertUserProjects(insertProject, user.userId(), user.projectIds());
                }
            }
            return null;
        }));
    }

    void createUser(UserDirectory.User user, @Nullable String accessKeySha256) throws IOException {
        run("create control-plane user", () -> database.inTransaction(connection -> {
            int nextOrdinal;
            try (var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT COALESCE(MAX(ordinal), -1) + 1 FROM cp_users")) {
                rows.next();
                nextOrdinal = rows.getInt(1);
            }
            try (var insertUser = connection.prepareStatement("""
                    INSERT INTO cp_users(user_id, ordinal, display_name, role_id, access_key_sha256)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                insertUser.setString(1, user.userId());
                insertUser.setInt(2, nextOrdinal);
                insertUser.setString(3, user.displayName());
                insertUser.setString(4, user.role());
                insertUser.setString(5, accessKeySha256);
                insertUser.executeUpdate();
            }
            if (!user.projectIds().isEmpty()) {
                try (var insertProject = connection.prepareStatement("""
                        INSERT INTO cp_user_projects(user_id, ordinal, project_id)
                        VALUES (?, ?, ?)
                        """)) {
                    insertUserProjects(insertProject, user.userId(), user.projectIds());
                }
            }
            return null;
        }));
    }

    void updateUser(UserDirectory.User user) throws IOException {
        run("update control-plane user", () -> database.inTransaction(connection -> {
            try (var update = connection.prepareStatement(
                    "UPDATE cp_users SET display_name = ?, role_id = ? WHERE user_id = ?")) {
                update.setString(1, user.displayName());
                update.setString(2, user.role());
                update.setString(3, user.userId());
                if (update.executeUpdate() != 1) {
                    throw new SQLException("User row was not found for update: " + user.userId());
                }
            }
            try (var clearProjects = connection.prepareStatement(
                    "DELETE FROM cp_user_projects WHERE user_id = ?")) {
                clearProjects.setString(1, user.userId());
                clearProjects.executeUpdate();
            }
            if (!user.projectIds().isEmpty()) {
                try (var insertProject = connection.prepareStatement("""
                        INSERT INTO cp_user_projects(user_id, ordinal, project_id)
                        VALUES (?, ?, ?)
                        """)) {
                    insertUserProjects(insertProject, user.userId(), user.projectIds());
                }
            }
            return null;
        }));
    }

    void updateAccessKeyHash(String userId, @Nullable String accessKeySha256) throws IOException {
        run("update control-plane user access key", () -> database.inTransaction(connection -> {
            try (var update = connection.prepareStatement(
                    "UPDATE cp_users SET access_key_sha256 = ? WHERE user_id = ?")) {
                update.setString(1, accessKeySha256);
                update.setString(2, userId);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("User row was not found for access key update: " + userId);
                }
            }
            return null;
        }));
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

    private static void insertUserProjects(PreparedStatement statement, String userId, List<String> projectIds)
            throws SQLException {
        int ordinal = 0;
        for (String projectId : projectIds) {
            statement.setString(1, userId);
            statement.setInt(2, ordinal++);
            statement.setString(3, projectId);
            statement.executeUpdate();
        }
    }
}
