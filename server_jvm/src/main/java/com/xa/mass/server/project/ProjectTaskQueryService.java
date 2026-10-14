package com.xa.mass.server.project;

import com.xa.mass.server.task.access.TaskCaller;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskView;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.directory.TaskDirectory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public final class ProjectTaskQueryService {
    private static final int READ_BATCH = 100;
    private final ProjectDirectory projects;
    private final TaskResourceCatalog tasks;
    private final TaskScoreBandCore scores;
    private final TaskDirectory directory;
    private final com.xa.mass.kernel.score.TaskItemScoreBandCore itemScores;

    public ProjectTaskQueryService(ProjectDirectory projects, TaskResourceCatalog tasks, TaskScoreBandCore scores, TaskDirectory directory, com.xa.mass.kernel.score.TaskItemScoreBandCore itemScores) {
        this.projects = projects; this.tasks = tasks; this.scores = scores; this.directory = directory; this.itemScores = itemScores;
    }

    public ProjectTasks list(TaskCaller caller, String projectId, int limit, @Nullable String userId) {
        caller.requireProject(projectId);
        userId = caller.selectUser(userId);
        var page = directory.list(projectId, userId, limit);
        try {
            var result = new ArrayList<Entry>();
            for (int from = 0; from < page.members().size(); from += READ_BATCH) {
                var batch = page.members().subList(from, Math.min(from + READ_BATCH, page.members().size()));
                var ids = batch.stream().map(TaskDirectory.Member::taskId).toList();
                var descriptors = tasks.loadTaskAllocationDescriptors(ids);
                for (var member : batch) {
                    var task = descriptors.get(member.taskId());
                    TaskAttribution attribution = task == null ? null : TaskAttribution.require(task.metadata());
                    if (attribution != null && (!projectId.equals(attribution.projectId()) || attribution.score() != member.attributionScore()))
                        throw new IllegalStateException("Task directory attribution differs from descriptor");
                    if (task != null) caller.requireTask(task);
                }
                var states = scores.getScoreStates(ids);
                for (var member : batch) {
                    var task = descriptors.get(member.taskId());
                    var attribution = task == null ? null : TaskAttribution.require(task.metadata());
                    // Missing descriptors cannot establish personal attribution; do not refill the batch.
                    if (attribution == null && userId != null) continue;
                    var state = states.get(member.taskId());
                    result.add(new Entry(member.taskId(), attribution == null ? null : attribution.createdAtMillis(),
                            task == null ? null : toView(task), state == null ? null : state.isInitial() ? "running-initial" : state.band().wireValue()));
                }
            }
            result.sort(Comparator.comparing(Entry::createdAtMillis, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Entry::taskId));
            return new ProjectTasks(projectId, List.copyOf(result), page.truncated());
        } catch (ServerException known) { throw known;
        } catch (RuntimeException error) { throw unavailable("project.listTasks", error); }
    }

    public java.util.Map<String, com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreCounts> stats(
            TaskCaller caller, String projectId, List<String> taskIds) {
        caller.requireProject(projectId);
        projects.require(projectId);
        if (taskIds == null || taskIds.isEmpty() || taskIds.size() > 100 || taskIds.stream().anyMatch(id -> id == null || id.isBlank()))
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "project.taskStats", null, null);
        var ids = List.copyOf(new java.util.LinkedHashSet<>(taskIds));
        try {
            var descriptors = tasks.loadTaskAllocationDescriptors(ids);
            for (String id : ids) {
                var task = descriptors.get(id);
                if (task == null || !projectId.equals(TaskAttribution.require(task.metadata()).projectId()))
                    throw new ServerException(ServerErrorCode.TASK_NOT_FOUND, "project.taskStats", null, null);
                caller.requireTask(task);
            }
            return itemScores.observeItemScoreCounts(ids);
        } catch (ServerException known) { throw known;
        } catch (RuntimeException error) { throw unavailable("project.taskStats", error); }
    }

    public record ProjectTasks(String projectId, List<Entry> tasks, boolean truncated) {}

    public Entry get(TaskCaller caller, String projectId, String taskId) {
        caller.requireProject(projectId);
        projects.require(projectId);
        try {
            var task = tasks.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
            var attribution = task == null ? null : TaskAttribution.read(task.metadata());
            if (attribution == null || !projectId.equals(attribution.projectId()))
                throw new ServerException(ServerErrorCode.TASK_NOT_FOUND, "project.getTask", null, null);
            caller.requireTask(task);
            var state = scores.getScoreStates(List.of(taskId)).get(taskId);
            return new Entry(taskId, attribution.createdAtMillis(), toView(task),
                    state == null ? null : state.isInitial() ? "running-initial" : state.band().wireValue());
        } catch (ServerException known) { throw known;
        } catch (RuntimeException error) { throw unavailable("project.getTask", error); }
    }

    public static TaskView toView(TaskDescriptor task) {
        var attribution = TaskAttribution.read(task.metadata());
        return new TaskView(task.taskId(), attribution == null ? null : attribution.projectId(), task.workerGroupId(),
                task.idleDisposition().name(), task.refill(), task.config(), task.name(), task.metadata());
    }
    public record Entry(String taskId, @Nullable Long createdAtMillis, @Nullable TaskView task, @Nullable String scoreBand) {}

    private static ServerException unavailable(String operation, Throwable error) {
        return new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, operation, null, error);
    }
}
