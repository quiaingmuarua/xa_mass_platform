package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.runtime.TaskRuntimeProfileResolver;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedWorkerSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolution;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.runtime.scheduling.TaskDispatchIntent;
import com.xa.mass.engine.runtime.scheduling.TaskPolicyPresetResolution;
import com.xa.mass.engine.runtime.scheduling.TaskPolicyPresetResolver;
import com.xa.mass.worker.runtime.routing.WorkerCandidateBucketPolicies;

import java.util.Objects;
import java.util.Set;

/**
 * Default behavior-neutral Scheduling Plane resolver.
 *
 * <p>This resolver mirrors current runtime profile and candidate-bucket behavior.
 * It introduces no storage-backed catalog and no new scheduling behavior.</p>
 */
public final class DefaultSchedulingPlaneResolver implements SchedulingPlaneResolver {

    private final TaskPolicyPresetResolver taskPolicyPresetResolver;
    private final WorkerCandidateBucketPolicies.ApprovedAttributeCandidateBucketPolicy candidateBucketPolicy;

    public DefaultSchedulingPlaneResolver() {
        this(new TaskPolicyPresetResolver(), WorkerCandidateBucketPolicies.defaultApprovedAttributePolicy());
    }

    public DefaultSchedulingPlaneResolver(TaskRuntimeProfileResolver taskRuntimeProfileResolver,
                                          WorkerCandidateBucketPolicies.ApprovedAttributeCandidateBucketPolicy candidateBucketPolicy) {
        this(new TaskPolicyPresetResolver(taskRuntimeProfileResolver), candidateBucketPolicy);
    }

    public DefaultSchedulingPlaneResolver(TaskPolicyPresetResolver taskPolicyPresetResolver,
                                          WorkerCandidateBucketPolicies.ApprovedAttributeCandidateBucketPolicy candidateBucketPolicy) {
        this.taskPolicyPresetResolver = Objects.requireNonNull(taskPolicyPresetResolver,
                "taskPolicyPresetResolver");
        this.candidateBucketPolicy = candidateBucketPolicy == null
                ? WorkerCandidateBucketPolicies.defaultApprovedAttributePolicy()
                : candidateBucketPolicy;
    }

    @Override
    public SchedulingPlaneResolution resolve(Task task) {
        TaskDispatchIntent intent = TaskDispatchIntent.fromTask(task);
        TaskPolicyPresetResolution presetResolution = taskPolicyPresetResolver.resolve(task);
        Set<String> candidateBucketKeys = Set.of(candidateBucketPolicy.exactCandidateBucketKeyForAttributes(
                TaskSharedConfig.routeAttributes(task)));
        return new SchedulingPlaneResolution(
                intent,
                ResolvedTaskSchedulingPolicy.from(task, presetResolution),
                ResolvedWorkerSchedulingPolicy.from(intent, candidateBucketKeys)
        );
    }
}
