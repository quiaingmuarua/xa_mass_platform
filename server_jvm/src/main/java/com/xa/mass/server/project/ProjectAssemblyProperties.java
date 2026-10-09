package com.xa.mass.server.project;

import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Host topology contribution; list overlays replace configuration declarations, not module Beans. */
@ConfigurationProperties(prefix = "xa.mass.project-assembly", ignoreUnknownFields = false)
public record ProjectAssemblyProperties(@DefaultValue List<ProjectDefinition> projects) {
    public ProjectAssemblyProperties {
        projects = projects == null ? List.of() : List.copyOf(projects);
        var ids = new HashSet<String>();
        for (var project : projects) {
            if (!ids.add(project.projectId())) throw new IllegalArgumentException("Duplicate Project ID");
        }
    }
}
