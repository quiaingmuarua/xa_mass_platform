package com.xa.mass.server.testing;

import com.xa.mass.server.project.ProjectAssemblyProperties;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.user.UserDirectory;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** Owner tests declare their users and grants and use the real directory admission. */
public final class TaskCallers {
    private TaskCallers() {}

    public static UserDirectory directory(UserDirectory.User... users) {
        try {
            var mapper = JsonMapper.builder().build();
            final UserDirectory.Seed defaults;
            try (var input = new ClassPathResource("users/development-directory.json").getInputStream()) {
                defaults = mapper.readValue(input, UserDirectory.Seed.class);
            }
            var entries = new ArrayList<>(List.of(users));
            if (entries.stream().noneMatch(user -> user.role().equals("admin")))
                entries.add(new UserDirectory.User("seedadmin", "Fixture administrator", "admin", List.of()));
            var projects = entries.stream().flatMap(user -> user.projectIds().stream()).distinct()
                    .map(id -> new ProjectDefinition(id, List.of("g"), null)).toList();
            return new UserDirectory(mapper, new ByteArrayResource(mapper.writeValueAsBytes(new UserDirectory.Seed(defaults.roles(), entries))),
                    new ProjectDirectory(new ProjectAssemblyProperties(projects), List.of()));
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    public static TaskCaller caller(String id, String role, String... projects) {
        return directory(new UserDirectory.User(id.strip(), id, role, List.of(projects))).resolveCaller(id, role);
    }
}
