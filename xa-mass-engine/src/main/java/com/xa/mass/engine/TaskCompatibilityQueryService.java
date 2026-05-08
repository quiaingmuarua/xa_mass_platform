package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.storage.api.TaskDetailStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Explicit compatibility query facade for bounded TaskMsg / TaskMsgAttempt
 * residue reads.
 *
 * <p>This service is intentionally named as compatibility residue so callers
 * do not mistake TaskMsg projection reads for the engine's default runtime
 * query model.</p>
 */
@CompatibilityProjectionOnly
public class TaskCompatibilityQueryService {

    private final TaskCompatibilityQueryPort compatibilityQueries;

    public TaskCompatibilityQueryService(TaskDetailStore taskDetailStore,
                                         Function<String, com.xa.mass.base.model.Task> taskLookup,
                                         BiFunction<String, String, Optional<ActiveLeaseRecord>> activeLeaseLookup,
                                         Function<String, List<ActiveLeaseRecord>> activeLeasesLookup) {
        this(new TaskCompatibilityProjectionAccess(
                taskDetailStore,
                taskLookup,
                activeLeaseLookup,
                activeLeasesLookup
        ));
    }

    TaskCompatibilityQueryService(TaskCompatibilityQueryPort compatibilityQueries) {
        this.compatibilityQueries = Objects.requireNonNull(compatibilityQueries, "compatibilityQueries");
    }

    public TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        return compatibilityQueries.getTaskMessageSnapshot(taskId, limit);
    }

    public TaskMsg getTaskMessageView(String taskId, String messageId) {
        return compatibilityQueries.getTaskMessageView(taskId, messageId);
    }

    public List<TaskMsgAttempt> getTaskMessageAttemptViews(String taskId, String messageId) {
        return compatibilityQueries.getTaskMessageAttemptViews(taskId, messageId);
    }

    public TaskMsgAttempt getLatestActiveTaskMessageAttemptView(String taskId, String messageId) {
        return compatibilityQueries.getLatestActiveTaskMessageAttemptView(taskId, messageId);
    }
}
