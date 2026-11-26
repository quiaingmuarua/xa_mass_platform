package com.xa.mass.server.project;

import com.xa.mass.server.assembly.controlplane.ControlPlaneDatabase;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Immutable startup Project topology combining host declarations (persisted in control-plane SQLite when
 * enabled, otherwise read from {@code xa.mass.project-assembly.projects}) and module-contributed Beans.
 *
 * <p>Reads never touch SQLite or prepare Runtime resources.
 */
@Service
public final class ProjectDirectory {
    private static final System.Logger LOG = System.getLogger(ProjectDirectory.class.getName());

    private final Map<String, ProjectView> projects;
    private final Map<String, String> inputOwners;

    public ProjectDirectory(ProjectAssemblyProperties properties, List<ProjectDefinition> declarations) {
        this(properties, declarations, null);
    }

    @Autowired
    public ProjectDirectory(ProjectAssemblyProperties properties, List<ProjectDefinition> declarations,
            @Autowired(required = false) @Nullable ControlPlaneDatabase controlPlane) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(declarations, "declarations");
        List<ProjectDefinition> sortedModules = declarations.stream()
                .sorted(Comparator.comparing(ProjectDefinition::projectId))
                .toList();
        List<ProjectDefinition> hostProjects;
        if (controlPlane != null && controlPlane.isEnabled()) {
            var store = new SqliteProjectDirectoryStore(controlPlane);
            try {
                if (!store.isEmpty()) {
                    hostProjects = store.loadAll();
                    LOG.log(System.Logger.Level.INFO,
                            "Loaded host Project directory from control-plane SQLite ({0} projects)",
                            hostProjects.size());
                } else if (!properties.projects().isEmpty()) {
                    // Validate merged declarations before writing so a conflicting seed leaves SQLite empty.
                    merge(properties.projects(), sortedModules);
                    store.bootstrap(properties.projects());
                    hostProjects = properties.projects();
                    LOG.log(System.Logger.Level.INFO,
                            "Bootstrapped control-plane SQLite host Project directory from configuration ({0} projects)",
                            hostProjects.size());
                } else {
                    hostProjects = List.of();
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("Failed to initialize control-plane SQLite Project directory", failure);
            }
        } else {
            hostProjects = properties.projects();
        }
        Merged merged = merge(hostProjects, sortedModules);
        this.projects = merged.projects();
        this.inputOwners = merged.inputOwners();
    }

    private static Merged merge(List<ProjectDefinition> hostProjects, List<ProjectDefinition> sortedModules) {
        var definitions = new ArrayList<ProjectDefinition>(hostProjects.size() + sortedModules.size());
        definitions.addAll(hostProjects);
        definitions.addAll(sortedModules);
        var result = new LinkedHashMap<String, ProjectView>();
        var owners = new LinkedHashMap<String, String>();
        for (var project : definitions) {
            if (project.exclusiveInputOwner() != null) owners.put(project.projectId(), project.exclusiveInputOwner());
            if (result.putIfAbsent(project.projectId(), new ProjectView(project.projectId(), project.workerGroupIds(),
                    "default".equals(project.projectId()) ? "registered" : "declared")) != null)
                throw new IllegalArgumentException("Duplicate Project ID across startup declarations: " + project.projectId());
        }
        return new Merged(Collections.unmodifiableMap(result), Map.copyOf(owners));
    }

    private record Merged(Map<String, ProjectView> projects, Map<String, String> inputOwners) {}

    public Map<String, ProjectView> projects() { return projects; }

    public @org.jspecify.annotations.Nullable String exclusiveInputOwner(String projectId) {
        return inputOwners.get(projectId);
    }

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
