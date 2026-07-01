package com.xa.mass.engine.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.ResultApplyCommand;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.RuntimeEpoch;

import java.util.Map;

public final class TaskRuntimeResultCommandMapper {

    private TaskRuntimeResultCommandMapper() {
    }

    public static ResultApplyCommand fromDispatchSubmitFailure(TaskDispatchBinding binding,
                                                              ResolvedTaskSchedulingPolicy policy,
                                                              RuntimeEpoch runtimeEpoch,
                                                              long observedAtMillis,
                                                              String failureReason,
                                                              long retryPolicyVersion,
                                                              long finalResultRetentionMillis) {
        return fromDispatchFailure(
                binding,
                policy,
                runtimeEpoch,
                observedAtMillis,
                failureReason,
                retryPolicyVersion,
                finalResultRetentionMillis,
                ResultApplySource.DISPATCH_SUBMIT_FAILURE);
    }

    public static ResultApplyCommand fromDispatchDeliveryFailure(TaskDispatchBinding binding,
                                                                ResolvedTaskSchedulingPolicy policy,
                                                                RuntimeEpoch runtimeEpoch,
                                                                long observedAtMillis,
                                                                String failureReason,
                                                                long retryPolicyVersion,
                                                                long finalResultRetentionMillis) {
        return fromDispatchFailure(
                binding,
                policy,
                runtimeEpoch,
                observedAtMillis,
                failureReason,
                retryPolicyVersion,
                finalResultRetentionMillis,
                ResultApplySource.DISPATCH_DELIVERY_FAILURE);
    }

    public static ResultApplyCommand fromLeaseTimeout(ActiveLeaseRepairCandidate candidate,
                                                     ResolvedTaskSchedulingPolicy policy,
                                                     RuntimeEpoch runtimeEpoch,
                                                     long observedAtMillis,
                                                     String failureReason,
                                                     long retryPolicyVersion,
                                                     long finalResultRetentionMillis) {
        if (candidate == null) {
            throw new IllegalArgumentException("active lease repair candidate is required");
        }
        return new ResultApplyCommand(
                candidate.taskId(),
                candidate.messageId(),
                candidate.leaseToken(),
                candidate.workerId(),
                candidate.attemptNo(),
                ResultApplySource.LEASE_TIMEOUT,
                false,
                Map.of(),
                normalizeReason(failureReason),
                TaskRuntimePolicySnapshotMapper.toRetryPolicySnapshot(policy, -1, retryPolicyVersion),
                TaskRuntimePolicySnapshotMapper.toResultFinalityPolicySnapshot(policy, finalResultRetentionMillis),
                runtimeEpoch,
                observedAtMillis);
    }

    private static ResultApplyCommand fromDispatchFailure(TaskDispatchBinding binding,
                                                         ResolvedTaskSchedulingPolicy policy,
                                                         RuntimeEpoch runtimeEpoch,
                                                         long observedAtMillis,
                                                         String failureReason,
                                                         long retryPolicyVersion,
                                                         long finalResultRetentionMillis,
                                                         ResultApplySource source) {
        if (binding == null) {
            throw new IllegalArgumentException("dispatch binding is required");
        }
        return new ResultApplyCommand(
                binding.taskId(),
                binding.messageId(),
                binding.leaseToken(),
                binding.workerId(),
                binding.attemptNo(),
                source,
                false,
                Map.of(),
                normalizeReason(failureReason),
                TaskRuntimePolicySnapshotMapper.toRetryPolicySnapshot(policy, -1, retryPolicyVersion),
                TaskRuntimePolicySnapshotMapper.toResultFinalityPolicySnapshot(policy, finalResultRetentionMillis),
                runtimeEpoch,
                observedAtMillis);
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "task runtime failure" : reason.trim();
    }
}
