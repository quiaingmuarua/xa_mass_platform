package com.xa.mass.server.task.history;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.server.task.retention.TaskRetentionProperties;
import java.sql.SQLException;
import java.util.*;
import org.springframework.stereotype.Service;

/** The only Redis-to-history handoff. It consumes sealed Owner observations, never repairs them. */
@Service
public final class TaskHistoryPublisher {
    private final TaskRuntime runtime;
    private final TaskItemScoreBandCore states;
    private final TaskHistoryStore store;
    private final String scope;
    private final TaskRetentionProperties properties;

    public TaskHistoryPublisher(TaskRuntime runtime, TaskItemScoreBandCore states, TaskHistoryStore store,
            RedisKeyspace keyspace, TaskRetentionProperties properties) {
        this.runtime = runtime; this.states = states; this.store = store;
        this.scope = keyspace.base(); this.properties = properties;
    }

    public void requirePublished(TaskDescriptor task, TaskDataState data, long now) throws SQLException {
        if (data.frozenAtMillis() == null) throw new IllegalStateException("History requires a frozen identity");
        var saved = store.get(scope, TaskHistoryStore.historyId(scope, task.taskId(), data.frozenAtMillis()), now);
        if (saved == null || !store.sameContent(saved.snapshot().task(), task))
            throw new IllegalStateException("Complete Task history is unavailable");
    }

    public void publish(TaskDescriptor task, TaskScoreState score, TaskDataState data, long now) throws SQLException {
        if (data.phase() != TaskDataPhase.FROZEN || data.frozenAtMillis() == null || score.terminatedAtMillis() == null)
            throw new IllegalStateException("History requires a terminal frozen Task");
        String id = TaskHistoryStore.historyId(scope, task.taskId(), data.frozenAtMillis());
        var counts = states.observeItemScoreCounts(List.of(task.taskId())).get(task.taskId());
        if (counts == null) throw new IllegalStateException("Task Item counts are unavailable");
        var snapshot = new TaskHistoryStore.Snapshot(id, scope, task, score.band().wireValue(),
                score.terminatedAtMillis(), data.frozenAtMillis(),
                Math.addExact(score.terminatedAtMillis(), properties.historyRetention().toMillis()), counts);
        var existing = store.get(scope, id, now);
        if (existing != null) {
            if (!store.sameContent(existing.snapshot(), snapshot)) throw new IllegalStateException("Published history differs from frozen Task");
            return;
        }
        store.begin(snapshot);
        scan(task.taskId(), id, true);
        scan(task.taskId(), id, false);
        if (!data.equals(runtime.loadTaskDataStates(List.of(task.taskId())).get(task.taskId())))
            throw new IllegalStateException("Task data changed during history publication");
        store.publish(id, now);
        requirePublished(task, data, now);
    }

    private void scan(String taskId, String historyId, boolean inputs) throws SQLException {
        String cursor = "0";
        var visited = new HashSet<String>();
        do {
            if (!visited.add(cursor)) throw new IllegalStateException("Frozen Task scan cursor repeated");
            List<String> ids;
            if (inputs) {
                var page = runtime.scanTaskItems(taskId, cursor, 100);
                ids = new ArrayList<>(page.items().keySet()); cursor = page.nextCursor();
            } else {
                var page = runtime.scanTaskItemResults(taskId, cursor, 100);
                ids = new ArrayList<>(page.results().keySet()); cursor = page.nextCursor();
            }
            // HSCAN COUNT is a hint, not a hard bound. All point reads and SQL batches remain <=100.
            for (int start = 0; start < ids.size(); start += 100) {
                var batch = ids.subList(start, Math.min(start + 100, ids.size()));
                var items = runtime.loadTaskItems(taskId, batch);
                var results = runtime.loadTaskItemResults(taskId, batch);
                var itemStates = states.getItemScoreStates(taskId, batch);
                var rows = new ArrayList<TaskHistoryStore.Row>();
                for (String messageId : batch) rows.add(new TaskHistoryStore.Row(messageId,
                        items.get(messageId), itemStates.get(messageId), results.get(messageId)));
                store.append(historyId, rows);
            }
        } while (!cursor.equals("0"));
    }
}
