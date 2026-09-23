package com.xa.mass.server.project;

import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Profile-owned topology; lists deliberately replace inherited profile declarations. */
@ConfigurationProperties(prefix = "xa.mass.project-assembly", ignoreUnknownFields = false)
public record ProjectAssemblyProperties(@DefaultValue List<Project> projects) {
    public ProjectAssemblyProperties {
        projects = projects == null ? List.of() : List.copyOf(projects);
        var ids = new HashSet<String>();
        for (var project : projects) {
            if (!ids.add(project.projectId())) throw new IllegalArgumentException("Duplicate Project ID");
        }
    }

    public record Project(String projectId, List<String> workerGroupIds) {
        public Project {
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException("projectId must be non-blank");
            }
            if (workerGroupIds == null || workerGroupIds.isEmpty()) {
                throw new IllegalArgumentException("Project must declare WorkerGroups");
            }
            workerGroupIds = List.copyOf(workerGroupIds);
            var groups = new HashSet<String>();
            for (String group : workerGroupIds) {
                if (group.isBlank() || !groups.add(group)) {
                    throw new IllegalArgumentException("Project WorkerGroups must be non-blank and unique");
                }
            }
        }
    }
}
