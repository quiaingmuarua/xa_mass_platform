package com.xa.mass.server.user;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.TaskUserId;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** One Server-process directory. The seed is read once and is never rewritten. */
@Service
public final class UserDirectory {
    private final Map<String, Role> roles = new LinkedHashMap<>();
    private final Map<String, User> users = new LinkedHashMap<>();
    private final ProjectDirectory projects;

    public UserDirectory(ObjectMapper mapper,
            @Value("${xa.mass.users.seed:classpath:users/development-directory.json}") Resource seed,
            ProjectDirectory projects) throws IOException {
        this.projects = projects;
        final Seed input;
        try (var stream = seed.getInputStream()) { input = mapper.readValue(stream, Seed.class); }
        for (var role : input.roles()) {
            if (!Set.of("admin", "operator").contains(role.id()) || roles.putIfAbsent(role.id(), role) != null)
                throw new IllegalArgumentException("Invalid or duplicate development role");
        }
        if (!roles.keySet().equals(Set.of("admin", "operator"))) throw new IllegalArgumentException("Both development roles are required");
        for (var user : input.users()) {
            var normalized = normalize(user);
            if (users.putIfAbsent(normalized.userId(), normalized) != null) throw new IllegalArgumentException("Duplicate development user");
        }
        if (users.values().stream().noneMatch(user -> user.role().equals("admin"))) throw new IllegalArgumentException("An initial admin is required");
    }

    public List<Role> roles() { return List.copyOf(roles.values()); }
    public synchronized List<User> users() { return List.copyOf(users.values()); }
    /** Point lookup only; account I/O must not hold the directory monitor. */
    public synchronized boolean containsUser(String userId) { return users.containsKey(userId); }

    /** One request's immutable identity and grants, resolved under the same gate as updates. */
    public synchronized TaskCaller resolveCaller(String userId, String declaredRole) {
        final String id;
        try {
            id = TaskUserId.normalize(userId);
            if (!roles.containsKey(declaredRole)) throw new IllegalArgumentException("Invalid development role");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ServerException(ServerErrorCode.INVALID_TASK_CALLER, "userDirectory.resolveCaller", null, invalid);
        }
        var user = users.get(id);
        if (user == null || !user.role().equals(declaredRole))
            throw new ServerException(ServerErrorCode.TASK_ACCESS_DENIED, "userDirectory.resolveCaller", null, null);
        return TaskCaller.fromDirectory(user, roles.get(user.role()));
    }

    public synchronized User create(TaskCaller caller, User input) {
        caller.requirePermission("users.manage");
        var user = normalize(input);
        if (users.containsKey(user.userId())) throw rejected("User already exists");
        user.projectIds().forEach(projects::require);
        users.put(user.userId(), user);
        return user;
    }

    public synchronized User update(TaskCaller caller, String userId, UpdateUser input) {
        caller.requirePermission("users.manage");
        if (!users.containsKey(userId)) throw rejected("User does not exist");
        if (Objects.equals(userId, caller.userId())) throw rejected("Use another administrator to edit the current user");
        var user = normalize(new User(userId, input.displayName(), input.role(), input.projectIds()));
        user.projectIds().forEach(projects::require);
        users.put(userId, user);
        return user;
    }

    private User normalize(User input) {
        try {
            String id = TaskUserId.normalize(input.userId());
            String name = Objects.requireNonNull(input.displayName()).strip();
            if (name.isEmpty() || name.length() > 80 || !roles.containsKey(input.role())) throw new IllegalArgumentException();
            var projects = List.copyOf(input.projectIds());
            if (projects.size() > 100 || projects.stream().anyMatch(String::isBlank) || projects.stream().distinct().count() != projects.size())
                throw new IllegalArgumentException();
            if (roles.get(input.role()).projectScope().equals("all") && !projects.isEmpty()) throw new IllegalArgumentException();
            return new User(id, name, input.role(), projects);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ServerException(ServerErrorCode.INVALID_USER_REQUEST, "userDirectory.validate", null, invalid);
        }
    }
    private static ServerException rejected(String reason) {
        return new ServerException(ServerErrorCode.INVALID_USER_REQUEST, "userDirectory.write", reason, null);
    }

    public record Seed(List<Role> roles, List<User> users) {}
    public record Role(String id, String label, List<String> permissions, String taskScope, String projectScope, String home) {
        public Role {
            permissions = List.copyOf(permissions);
            if (label == null || label.isBlank() || !Set.of("all", "own").contains(taskScope)
                    || !Set.of("all", "assigned").contains(projectScope) || home == null || !home.startsWith("/") || home.startsWith("//"))
                throw new IllegalArgumentException("Invalid role definition");
        }
    }
    public record User(@NotBlank @Pattern(regexp = "[1-9a-z][0-9a-z]{3,8}") String userId,
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Pattern(regexp = "admin|operator") String role,
            @NotNull @Size(max = 100) List<@NotBlank String> projectIds) {}
    public record UpdateUser(@NotBlank @Size(max = 80) String displayName,
            @NotBlank @Pattern(regexp = "admin|operator") String role,
            @NotNull @Size(max = 100) List<@NotBlank String> projectIds) {}
}
