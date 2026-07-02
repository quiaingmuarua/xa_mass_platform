package com.xa.mass.engine.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeResultFact;

import java.util.Map;

public final class TaskRuntimeResultFactMapper {

    private TaskRuntimeResultFactMapper() {
    }

    public static RuntimeResultFact fromDispatchSubmitFailure(TaskDispatchBinding binding,
                                                             RuntimeEpoch runtimeEpoch,
                                                             long observedAtMillis,
                                                             String failureReason) {
        return fromDispatchFailure(
                binding,
                runtimeEpoch,
                observedAtMillis,
                failureReason,
                ResultApplySource.DISPATCH_SUBMIT_FAILURE);
    }

    public static RuntimeResultFact fromDispatchDeliveryFailure(TaskDispatchBinding binding,
                                                               RuntimeEpoch runtimeEpoch,
                                                               long observedAtMillis,
                                                               String failureReason) {
        return fromDispatchFailure(
                binding,
                runtimeEpoch,
                observedAtMillis,
                failureReason,
                ResultApplySource.DISPATCH_DELIVERY_FAILURE);
    }

    public static RuntimeResultFact fromLeaseTimeout(ActiveLeaseRepairCandidate candidate,
                                                    RuntimeEpoch runtimeEpoch,
                                                    long observedAtMillis,
                                                    String failureReason) {
        if (candidate == null) {
            throw new IllegalArgumentException("active lease repair candidate is required");
        }
        return new RuntimeResultFact(
                candidate.taskId(),
                candidate.messageId(),
                candidate.leaseToken(),
                candidate.workerId(),
                candidate.attemptNo(),
                ResultApplySource.LEASE_TIMEOUT,
                false,
                Map.of(),
                normalizeReason(failureReason),
                runtimeEpoch,
                observedAtMillis);
    }

    private static RuntimeResultFact fromDispatchFailure(TaskDispatchBinding binding,
                                                        RuntimeEpoch runtimeEpoch,
                                                        long observedAtMillis,
                                                        String failureReason,
                                                        ResultApplySource source) {
        if (binding == null) {
            throw new IllegalArgumentException("dispatch binding is required");
        }
        return new RuntimeResultFact(
                binding.taskId(),
                binding.messageId(),
                binding.leaseToken(),
                binding.workerId(),
                binding.attemptNo(),
                source,
                false,
                Map.of(),
                normalizeReason(failureReason),
                runtimeEpoch,
                observedAtMillis);
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "task runtime failure" : reason.trim();
    }
}
