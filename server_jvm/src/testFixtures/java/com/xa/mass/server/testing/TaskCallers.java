package com.xa.mass.server.testing;

import com.xa.mass.server.project.ProjectAssemblyProperties;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.user.UserDirectory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owner tests declare their users and grants and use the real directory admission. Every fixture user receives
 * the documented public development key {@code xak_dev_<userId>}.
 */
public final class TaskCallers {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private TaskCallers() {}

    /** The public development access key for a built-in or fixture user. */
    public static String devKey(String userId) { return "xak_dev_" + userId; }

    /** One Authorization header for that public development key. */
    public static Map<String, String> devHeaders(String userId) { return Map.of("Authorization", "Bearer " + devKey(userId)); }

    public static UserDirectory directory(UserDirectory.User... users) {
        try {
            var entries = seedUsers(users);
            var projects = entries.stream().flatMap(user -> user.projectIds().stream()).distinct()
                    .map(id -> new ProjectDefinition(id, List.of("g"), null)).toList();
            return new UserDirectory(MAPPER, new ByteArrayResource(MAPPER.writeValueAsBytes(new UserDirectory.Seed(defaultRoles(), entries))),
                    new ProjectDirectory(new ProjectAssemblyProperties(projects), List.of()));
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    /** A persisted copy of the built-in development directory in {@code directory}; close it after the test. */
    public static UserDirectory writableDevelopmentDirectory(Path directory, ProjectDirectory projects) {
        try {
            Path file = Files.createDirectories(directory).resolve("users.json");
            try (var input = new ClassPathResource("users/development-directory.json").getInputStream()) {
                Files.write(file, input.readAllBytes());
            }
            return new UserDirectory(MAPPER, file, projects);
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    public static TaskCaller caller(String id, String role, String... projects) {
        return directory(new UserDirectory.User(id.strip(), id, role, List.of(projects))).authenticate(devKey(id.strip()));
    }

    private static List<UserDirectory.SeedUser> seedUsers(UserDirectory.User... users) {
        var entries = new ArrayList<UserDirectory.SeedUser>();
        for (var user : users) entries.add(seed(user));
        if (entries.stream().noneMatch(user -> user.role().equals("admin")))
            entries.add(seed(new UserDirectory.User("seedadmin", "Fixture administrator", "admin", List.of())));
        return entries;
    }

    private static UserDirectory.SeedUser seed(UserDirectory.User user) {
        return new UserDirectory.SeedUser(user.userId(), user.displayName(), user.role(), user.projectIds(),
                UserDirectory.hash(devKey(user.userId().strip())));
    }

    private static List<UserDirectory.Role> defaultRoles() throws IOException {
        try (var input = new ClassPathResource("users/development-directory.json").getInputStream()) {
            return MAPPER.readValue(input, UserDirectory.Seed.class).roles();
        }
    }
}
