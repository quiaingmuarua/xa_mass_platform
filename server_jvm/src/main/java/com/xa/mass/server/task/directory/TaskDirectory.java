package com.xa.mass.server.task.directory;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.ProjectDirectory;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** One lookup index per Project. It never owns Task execution state. */
@Service
public final class TaskDirectory implements AutoCloseable {
    private static final String ENSURE = """
            local kind = redis.call('TYPE', KEYS[1]).ok
            if kind ~= 'none' and kind ~= 'zset' then
              return redis.error_reply('Task directory has invalid type')
            end
            local current = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if current then
              if tonumber(current) ~= tonumber(ARGV[2]) then
                return redis.error_reply('Task directory attribution differs')
              end
              return 0
            end
            return redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
            """;
    private final RedisClient redis;
    private final RedisKeyspace keyspace;
    private final ProjectDirectory projects;
    private final TaskResourceCatalog tasks;
    private final TaskScoreBandCore scores;
    private final WorkerResourceCatalog groups;
    private final OperationGuard operations;
    private volatile StatefulRedisConnection<String, String> connection;
    private boolean closed;

    public TaskDirectory(RedisClient redis, RedisKeyspace keyspace, ProjectDirectory projects,
            TaskResourceCatalog tasks, TaskScoreBandCore scores, WorkerResourceCatalog groups, OperationGuard operations) {
        this.redis = redis; this.keyspace = keyspace; this.projects = projects;
        this.tasks = tasks; this.scores = scores; this.groups = groups; this.operations = operations;
    }

    public ActionOutcome ensure(String taskId) {
        if (taskId == null || taskId.isBlank()) throw invalid("Task ID is required", null);
        return operations.taskMutation(taskId, () -> {
            try {
                var task = tasks.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
                if (task == null) throw new ServerException(ServerErrorCode.TASK_NOT_FOUND, "taskDirectory.ensure", null, null);
                final TaskAttribution attribution;
                try { attribution = TaskAttribution.require(task.metadata()); }
                catch (IllegalArgumentException invalid) { throw invalid("Task has no valid Server attribution", invalid); }
                projects.requireWorkerGroup(attribution.projectId(), task.workerGroupId());
                if (groups.getWorkerGroupDescriptors(List.of(task.workerGroupId())).get(task.workerGroupId()) == null)
                    throw invalid("Task Group is unavailable", null);
                if (scores.getScoreStates(List.of(taskId)).get(taskId) == null)
                    throw new IllegalStateException("Task Score is missing");
                Long changed = commands().eval(ENSURE, ScriptOutputType.INTEGER,
                        new String[]{key(attribution.projectId())}, taskId, Long.toString(attribution.score()));
                if (changed == null) throw new IllegalStateException("Directory write was not confirmed");
                return changed == 0 ? ActionOutcome.unchanged() : ActionOutcome.applied();
            } catch (ServerException known) { throw known;
            } catch (RuntimeException failure) { throw unavailable("ensure", failure); }
        });
    }

    public Batch list(String projectId, @Nullable String userId, int limit) {
        projects.require(projectId);
        if (limit < 1 || limit > 1000) throw invalid("limit must be in 1..1000", null);
        final Long userScore;
        try { userScore = userId == null ? null : TaskUserId.score(userId); }
        catch (IllegalArgumentException invalid) { throw invalid(invalid.getMessage(), invalid); }
        try {
            var rows = userScore == null ? commands().zrangeWithScores(key(projectId), 0, limit)
                    : commands().zrangebyscoreWithScores(key(projectId), Range.create(userScore, userScore + 63), Limit.create(0, limit + 1));
            var result = new java.util.ArrayList<Member>();
            for (var row : rows) {
                if (row.getValue().isBlank()) throw new IllegalStateException("Task directory member is blank");
                TaskUserId.validateScore(row.getScore());
                if (result.size() < limit) result.add(new Member(row.getValue(), (long) row.getScore()));
            }
            return new Batch(List.copyOf(result), rows.size() > limit);
        } catch (RuntimeException failure) { throw unavailable("list", failure); }
    }

    public record Member(String taskId, long attributionScore) {}
    public record Batch(List<Member> members, boolean truncated) { public Batch { members = List.copyOf(members); } }

    public boolean contains(String projectId, String taskId) {
        try {
            Double score = commands().zscore(key(projectId), taskId);
            if (score == null) return false;
            TaskUserId.validateScore(score);
            return true;
        } catch (RuntimeException failure) { throw unavailable("contains", failure); }
    }

    private String key(String projectId) {
        return keyspace.base() + ":server:task-directory:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(projectId.getBytes(StandardCharsets.UTF_8));
    }
    private RedisCommands<String, String> commands() {
        synchronized (this) {
            if (closed) throw new IllegalStateException("Task directory is closed");
            if (connection == null || !connection.isOpen()) connection = redis.connect();
            return connection.sync();
        }
    }
    @Override public synchronized void close() {
        closed = true;
        if (connection != null) { connection.close(); connection = null; }
    }
    private static ServerException invalid(String message, Throwable cause) {
        return new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskDirectory.validate", message, cause);
    }
    private static ServerException unavailable(String operation, Throwable cause) {
        return new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskDirectory." + operation, null, cause);
    }
}
