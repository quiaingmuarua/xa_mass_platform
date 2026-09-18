package com.xa.mass.server.project;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskView;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public final class ProjectTaskQueryService {
    private final ProjectDirectory projects;
    private final TaskResourceCatalog tasks;
    private final TaskScoreBandCore scores;

    public ProjectTaskQueryService(ProjectDirectory projects, TaskResourceCatalog tasks, TaskScoreBandCore scores) {
        this.projects = projects;
        this.tasks = tasks;
        this.scores = scores;
    }

    public ProjectTasks list(String projectId, int limit) {
        projects.require(projectId);
        if (limit < 1 || limit > 1000) throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                "project.listTasks", "limit must be in 1..1000", null);
        try {
            var page = tasks.listProjectTasks(projectId, limit);
            var ids = page.tasks().stream().map(TaskResourceCatalog.ProjectTaskEntry::taskId).toList();
            if (ids.isEmpty()) return new ProjectTasks(projectId, List.of(), page.truncated());
            var descriptors = tasks.loadTaskAllocationDescriptors(ids);
            var states = scores.getScoreStates(ids);
            var rows = page.tasks().stream().map(entry -> {
                var descriptor = descriptors.get(entry.taskId());
                if (descriptor != null && !projectId.equals(descriptor.projectId())) {
                    throw new IllegalStateException("Project Task membership is corrupt");
                }
                var state = states.get(entry.taskId());
                return new Entry(entry.taskId(), entry.createdAtMillis(), descriptor == null ? null :
                        new TaskView(descriptor.taskId(), descriptor.projectId(), descriptor.workerGroupId(),
                                descriptor.idleDisposition().name(), descriptor.refill(), descriptor.config()),
                        state == null ? null : state.isInitial() ? "running-initial" : state.band().wireValue());
            }).toList();
            return new ProjectTasks(projectId, rows, page.truncated());
        } catch (RuntimeException error) {
            throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "project.listTasks", null, error);
        }
    }

    public record ProjectTasks(String projectId, List<Entry> tasks, boolean truncated) {}
    public record Entry(String taskId, long createdAtMillis, @Nullable TaskView task, @Nullable String scoreBand) {}
}
