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
    private final Map<String, String> inputOwners;

    public ProjectDirectory(ProjectAssemblyProperties properties, List<ProjectDefinition> declarations) {
        var definitions = new ArrayList<>(properties.projects());
        definitions.addAll(declarations.stream().sorted(Comparator.comparing(ProjectDefinition::projectId)).toList());
        var result = new LinkedHashMap<String, ProjectView>();
        var owners = new LinkedHashMap<String, String>();
        for (var project : definitions) {
            if (project.exclusiveInputOwner() != null) owners.put(project.projectId(), project.exclusiveInputOwner());
            if (result.putIfAbsent(project.projectId(), new ProjectView(project.projectId(), project.workerGroupIds(),
                    "default".equals(project.projectId()) ? "registered" : "declared")) != null)
                throw new IllegalArgumentException("Duplicate Project ID across startup declarations: " + project.projectId());
        }
        projects = Collections.unmodifiableMap(result);
        inputOwners = Map.copyOf(owners);
    }

    public Map<String, ProjectView> projects() { return projects; }

    public void requireInputOwner(String projectId, String inputOwner) {
        require(projectId);
        if (inputOwners.containsKey(projectId) && !inputOwners.get(projectId).equals(inputOwner))
            throw new ServerException(ServerErrorCode.TASK_ACCESS_DENIED, "project.inputOwner", "Project requires its business input entry", null);
    }

    public ProjectView require(String projectId) {
        var project = projects.get(projectId);
        if (project == null) throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                "project.require", "Project is not configured", null);
        return project;
    }

    public void requireWorkerGroup(String projectId, String workerGroupId) {
        if (workerGroupId == null || workerGroupId.isBlank() || !require(projectId).allowsWorkerGroup(workerGroupId))
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    "project.requireWorkerGroup", "WorkerGroup is not declared by Project", null);
    }

    public record ProjectView(String projectId, List<String> workerGroupIds, String workerGroupSource) {
        public ProjectView { workerGroupIds = List.copyOf(workerGroupIds); }
        /** Scope only; the operation must still point-read registration before writes. */
        public boolean allowsWorkerGroup(String group) {
            return "registered".equals(workerGroupSource) || workerGroupIds.contains(group);
        }
    }
}
