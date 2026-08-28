package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.ServiceabilityPolarity;
import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.WorkerServiceabilityObservation;
import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.WorkerSweepPage;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime.ProbeRequestOfferStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerServiceabilityDispatchPolicyTest {

    @Test
    void policyChoosesExcludedColdParkAndOffersRemainingWorkers() {
        WorkerServiceabilityDispatchMechanism mechanism = mock(
                WorkerServiceabilityDispatchMechanism.class
        );
        WorkerCandidateReference excludedRef = reference("excluded");
        WorkerCandidateReference offeredRef = reference("offered");
        when(mechanism.observePreEpochHot(
                eq("group-1"), eq(1_000L), any(), eq(80)
        )).thenReturn(new WorkerSweepPage(
                List.of(excludedRef, offeredRef),
                mock(WorkerSweepCursor.class)
        ));
        when(mechanism.observeRecovery(anyString(), any(), anyInt()))
                .thenReturn(emptyPage());
        WorkerServiceabilityObservation excluded = observation(
                "excluded",
                "system-polling",
                excludedRef
        );
        WorkerServiceabilityObservation offered = observation(
                "offered",
                "adapter-1",
                offeredRef
        );
        when(mechanism.recheck(
                "group-1",
                List.of(excludedRef, offeredRef)
        )).thenReturn(List.of(excluded, offered));
        WorkerServiceabilityRuntime serviceability = mock(
                WorkerServiceabilityRuntime.class
        );
        when(serviceability.offerProbeRequests(
                "adapter-1",
                List.of("offered")
        )).thenReturn(Map.of(
                "offered",
                ProbeRequestOfferStatus.OFFERED
        ));
        WorkerServiceabilityDispatchPolicy policy =
                new WorkerServiceabilityDispatchPolicy(
                        mechanism,
                        serviceability,
                        () -> 10_000
                );

        assertEquals(1, policy.dispatchProbes(
                List.of(task("task-1", "group-1")),
                config(),
                1_000
        ));
        verify(mechanism).coldParkExcluded(List.of(excluded), 5);
        verify(serviceability).offerProbeRequests(
                "adapter-1",
                List.of("offered")
        );
    }

    private static WorkerServiceabilityDispatchConfig config() {
        return WorkerServiceabilityDispatchConfig.defaults();
    }

    private static WorkerSweepPage emptyPage() {
        return new WorkerSweepPage(
                List.of(),
                WorkerSweepCursor.start()
        );
    }

    private static WorkerCandidateReference reference(String workerId) {
        WorkerCandidateReference reference = mock(
                WorkerCandidateReference.class
        );
        when(reference.workerId()).thenReturn(workerId);
        when(reference.workerGroupId()).thenReturn("group-1");
        return reference;
    }

    private static WorkerServiceabilityObservation observation(
            String workerId,
            String endpoint,
            WorkerCandidateReference reference
    ) {
        return new WorkerServiceabilityObservation(
                "group-1",
                workerId,
                ServiceabilityPolarity.HOT,
                100,
                0,
                endpoint,
                reference
        );
    }

    private static DueTaskObservation task(
            String taskId,
            String workerGroupId
    ) {
        return new DueTaskObservation(
                taskId,
                mock(TaskSchedulingReference.class),
                new TaskDescriptor(
                        taskId,
                        workerGroupId,
                        WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                        TaskIdleDisposition.PARK_WHEN_IDLE,
                        null,
                        Map.of(
                                "priority", "0",
                                "maximumCandidateWorkers", "1",
                                "maxRetryTimes", "1"
                        )
                )
        );
    }
}
