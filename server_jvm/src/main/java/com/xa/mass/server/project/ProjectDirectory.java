package com.xa.mass.server.project;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Immutable configuration only. Reading this directory never prepares Runtime resources. */
@Service
public final class ProjectDirectory {
    private final Map<String, ProjectView> projects;

    public ProjectDirectory(ProjectAssemblyProperties properties) {
        var result = new LinkedHashMap<String, ProjectView>();
        for (var project : properties.projects()) {
            var tasks = new LinkedHashMap<String, String>();
            for (String group : project.workerGroupIds()) {
                tasks.put(group, managedTaskId(project.projectId(), group));
            }
            result.put(project.projectId(), new ProjectView(project.projectId(), tasks));
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

    public String requireManagedTaskId(String projectId, String workerGroupId) {
        String taskId = require(projectId).managedTaskIds().get(workerGroupId);
        if (taskId == null) throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                "project.requireManagedTaskId", "WorkerGroup is not declared by Project", null);
        return taskId;
    }

    private static String managedTaskId(String project, String group) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return "project-rpc-" + encoder.encodeToString(project.getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(group.getBytes(StandardCharsets.UTF_8));
    }

    public record ProjectView(String projectId, Map<String, String> managedTaskIds) {
        public ProjectView { managedTaskIds = Collections.unmodifiableMap(new LinkedHashMap<>(managedTaskIds)); }
    }
}
