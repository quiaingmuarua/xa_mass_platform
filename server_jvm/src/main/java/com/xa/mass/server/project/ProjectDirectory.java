package com.xa.mass.server.project;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import org.springframework.stereotype.Service;

/** Immutable configuration and module declarations. Reads never prepare Runtime resources. */
@Service
public final class ProjectDirectory {
    private final Map<String, ProjectView> projects;

    public ProjectDirectory(ProjectAssemblyProperties properties, List<ProjectDefinition> declarations) {
        var definitions = new ArrayList<>(properties.projects());
        definitions.addAll(declarations.stream().sorted(Comparator.comparing(ProjectDefinition::projectId)).toList());
        var result = new LinkedHashMap<String, ProjectView>();
        for (var project : definitions) {
            if (result.putIfAbsent(project.projectId(), new ProjectView(project.projectId(), project.workerGroupIds())) != null)
                throw new IllegalArgumentException("Duplicate Project ID across startup declarations: " + project.projectId());
        }
        projects = Collections.unmodifiableMap(result);
    }

    public Map<String, ProjectView> projects() { return projects; }

    public ProjectView require(String projectId) {
        var project = projects.get(projectId);
        if (project == null) throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                "project.require", "Project is not configured", null);
        return project;
    }

    public void requireWorkerGroup(String projectId, String workerGroupId) {
        if (!require(projectId).workerGroupIds().contains(workerGroupId))
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    "project.requireWorkerGroup", "WorkerGroup is not declared by Project", null);
    }

    public record ProjectView(String projectId, List<String> workerGroupIds) {
        public ProjectView { workerGroupIds = List.copyOf(workerGroupIds); }
    }
}
