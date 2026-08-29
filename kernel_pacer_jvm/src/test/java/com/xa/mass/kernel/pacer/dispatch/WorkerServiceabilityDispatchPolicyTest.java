package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.ServiceabilityPolarity;
import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.WorkerServiceabilityObservation;
import com.xa.mass.kernel.pacer.dispatch.WorkerServiceabilityDispatchMechanism.WorkerSweepPage;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime.ProbeRequestOfferStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WorkerServiceabilityDispatchPolicyTest {

    @Test
    void visitsAllGroupsInTaskOrderUsingOneGlobalProbeBudget() {
        WorkerServiceabilityDispatchMechanism mechanism = mock(
                WorkerServiceabilityDispatchMechanism.class
        );
        WorkerCandidateReference firstRef = reference(
                "group-1",
                "worker-1"
        );
        WorkerCandidateReference secondRef = reference(
                "group-2",
                "worker-2"
        );
        when(mechanism.observePreEpochHot(
                eq("group-1"), eq(1_000L), any(), eq(100)
        )).thenReturn(page(firstRef));
        when(mechanism.observePreEpochHot(
                eq("group-2"), eq(1_000L), any(), eq(99)
        )).thenReturn(page(secondRef));
        WorkerServiceabilityObservation first = observation(
                "group-1",
                "worker-1",
                "adapter-1",
                firstRef
        );
        WorkerServiceabilityObservation second = observation(
                "group-2",
                "worker-2",
                "adapter-2",
                secondRef
        );
        when(mechanism.recheck("group-1", List.of(firstRef)))
                .thenReturn(List.of(first));
        when(mechanism.recheck("group-2", List.of(secondRef)))
                .thenReturn(List.of(second));
        when(mechanism.holdForProbe(List.of(first)))
                .thenReturn(List.of(first));
        when(mechanism.holdForProbe(List.of(second)))
                .thenReturn(List.of(second));
        WorkerServiceabilityRuntime serviceability = mock(
                WorkerServiceabilityRuntime.class
        );
        when(serviceability.offerProbeRequests(
                "adapter-1",
                List.of("worker-1")
        )).thenReturn(Map.of(
                "worker-1",
                ProbeRequestOfferStatus.OFFERED
        ));
        when(serviceability.offerProbeRequests(
                "adapter-2",
                List.of("worker-2")
        )).thenReturn(Map.of(
                "worker-2",
                ProbeRequestOfferStatus.OFFERED
        ));

        assertEquals(2, policy(mechanism, serviceability).dispatchProbes(
                List.of("group-1", "group-2"),
                config(100),
                1_000
        ));

        InOrder order = inOrder(mechanism, serviceability);
        order.verify(mechanism).observePreEpochHot(
                eq("group-1"), eq(1_000L), any(), eq(100)
        );
        order.verify(mechanism).holdForProbe(List.of(first));
        order.verify(serviceability).offerProbeRequests(
                "adapter-1",
                List.of("worker-1")
        );
        order.verify(mechanism).observePreEpochHot(
                eq("group-2"), eq(1_000L), any(), eq(99)
        );
        order.verify(mechanism).holdForProbe(List.of(second));
        order.verify(serviceability).offerProbeRequests(
                "adapter-2",
                List.of("worker-2")
        );
    }

    @Test
    void heldWorkersConsumeBudgetEvenWhenOffersAreRejected() {
        WorkerServiceabilityDispatchMechanism mechanism = mock(
                WorkerServiceabilityDispatchMechanism.class
        );
        List<WorkerCandidateReference> references = new ArrayList<>();
        List<WorkerServiceabilityObservation> observations =
                new ArrayList<>();
        List<String> workerIds = new ArrayList<>();
        LinkedHashMap<String, ProbeRequestOfferStatus> rejected =
                new LinkedHashMap<>();
        for (int index = 0; index < 100; index++) {
            String workerId = "worker-" + index;
            WorkerCandidateReference reference = reference(
                    "group-1",
                    workerId
            );
            references.add(reference);
            observations.add(observation(
                    "group-1",
                    workerId,
                    "adapter-1",
                    reference
            ));
            workerIds.add(workerId);
            rejected.put(workerId, ProbeRequestOfferStatus.CAPACITY);
        }
        when(mechanism.observePreEpochHot(
                eq("group-1"), eq(1_000L), any(), eq(100)
        )).thenReturn(new WorkerSweepPage(
                references,
                mock(WorkerSweepCursor.class)
        ));
        when(mechanism.recheck("group-1", references))
                .thenReturn(observations);
        when(mechanism.holdForProbe(observations))
                .thenReturn(observations);
        WorkerServiceabilityRuntime serviceability = mock(
                WorkerServiceabilityRuntime.class
        );
        when(serviceability.offerProbeRequests("adapter-1", workerIds))
                .thenReturn(rejected);

        assertEquals(0, policy(mechanism, serviceability).dispatchProbes(
                List.of("group-1", "group-2"),
                config(100),
                1_000
        ));

        verify(mechanism, never()).observePreEpochHot(
                eq("group-2"), eq(1_000L), any(), anyInt()
        );
    }

    @Test
    void nonEmptyHotPageIsHeldBeforeOfferAndSkipsRecovery() {
        WorkerServiceabilityDispatchMechanism mechanism = mock(
                WorkerServiceabilityDispatchMechanism.class
        );
        WorkerCandidateReference excludedRef = reference("excluded");
        WorkerCandidateReference offeredRef = reference("offered");
        when(mechanism.observePreEpochHot(
                eq("group-1"), eq(1_000L), any(), eq(100)
        )).thenReturn(new WorkerSweepPage(
                List.of(excludedRef, offeredRef),
                mock(WorkerSweepCursor.class)
        ));
        WorkerServiceabilityObservation excluded = observation(
                "excluded",
                "system-polling",
                ServiceabilityPolarity.HOT,
                100,
                0,
                excludedRef
        );
        WorkerServiceabilityObservation offered = observation(
                "offered",
                "adapter-1",
                ServiceabilityPolarity.HOT,
                100,
                0,
                offeredRef
        );
        when(mechanism.recheck(
                "group-1",
                List.of(excludedRef, offeredRef)
        )).thenReturn(List.of(excluded, offered));
        when(mechanism.holdForProbe(List.of(offered)))
                .thenReturn(List.of(offered));
        WorkerServiceabilityRuntime serviceability = serviceability(
                "adapter-1",
                "offered"
        );
        WorkerServiceabilityDispatchPolicy policy = policy(
                mechanism,
                serviceability
        );

        assertEquals(1, policy.dispatchProbes(
                List.of("group-1"),
                config(100),
                1_000
        ));

        verify(mechanism, never()).observeRecovery(
                eq("group-1"), any(), eq(100)
        );
        InOrder order = inOrder(mechanism, serviceability);
        order.verify(mechanism).coldPark(List.of(excluded), 5);
        order.verify(mechanism).holdForProbe(List.of(offered));
        order.verify(serviceability).offerProbeRequests(
                "adapter-1",
                List.of("offered")
        );
    }

    @Test
    void emptyHotPageFallsThroughToDueRecoveryAndAdvancesBeforeOffer() {
        WorkerServiceabilityDispatchMechanism mechanism = mock(
                WorkerServiceabilityDispatchMechanism.class
        );
        WorkerCandidateReference recoveryRef = reference("recovery");
        when(mechanism.observePreEpochHot(
                eq("group-1"), eq(1_000L), any(), eq(100)
        )).thenReturn(emptyPage());
        when(mechanism.observeRecovery(
                eq("group-1"), any(), eq(100)
        )).thenReturn(new WorkerSweepPage(
                List.of(recoveryRef),
                mock(WorkerSweepCursor.class)
        ));
        WorkerServiceabilityObservation recovery = observation(
                "recovery",
                "adapter-1",
                ServiceabilityPolarity.RECOVERY,
                9_000,
                0,
                recoveryRef
        );
        when(mechanism.recheck("group-1", List.of(recoveryRef)))
                .thenReturn(List.of(recovery));
        when(mechanism.holdForProbe(List.of(recovery)))
                .thenReturn(List.of(recovery));
        WorkerServiceabilityRuntime serviceability = serviceability(
                "adapter-1",
                "recovery"
        );
        WorkerServiceabilityDispatchPolicy policy = policy(
                mechanism,
                serviceability
        );

        assertEquals(1, policy.dispatchProbes(
                List.of("group-1"),
                config(100),
                1_000
        ));

        InOrder order = inOrder(mechanism, serviceability);
        order.verify(mechanism).holdForProbe(List.of(recovery));
        order.verify(serviceability).offerProbeRequests(
                "adapter-1",
                List.of("recovery")
        );
    }

    @Test
    void exhaustedRecoveryIsColdParkedWithoutProbeOffer() {
        WorkerServiceabilityDispatchMechanism mechanism = mock(
                WorkerServiceabilityDispatchMechanism.class
        );
        WorkerCandidateReference recoveryRef = reference("recovery");
        when(mechanism.observePreEpochHot(
                eq("group-1"), eq(1_000L), any(), eq(100)
        )).thenReturn(emptyPage());
        when(mechanism.observeRecovery(
                eq("group-1"), any(), eq(100)
        )).thenReturn(new WorkerSweepPage(
                List.of(recoveryRef),
                mock(WorkerSweepCursor.class)
        ));
        WorkerServiceabilityObservation recovery = observation(
                "recovery",
                "adapter-1",
                ServiceabilityPolarity.RECOVERY,
                9_000,
                5,
                recoveryRef
        );
        when(mechanism.recheck("group-1", List.of(recoveryRef)))
                .thenReturn(List.of(recovery));
        WorkerServiceabilityRuntime serviceability = mock(
                WorkerServiceabilityRuntime.class
        );
        WorkerServiceabilityDispatchPolicy policy = policy(
                mechanism,
                serviceability
        );

        assertEquals(0, policy.dispatchProbes(
                List.of("group-1"),
                config(100),
                1_000
        ));

        verify(mechanism).coldPark(List.of(recovery), 5);
        verify(mechanism).holdForProbe(List.of());
        verify(serviceability, never()).offerProbeRequests(
                any(),
                any()
        );
    }

    @Test
    void rejectedProbeOfferDoesNotRollBackSuccessfulHold() {
        WorkerServiceabilityDispatchMechanism mechanism = mock(
                WorkerServiceabilityDispatchMechanism.class
        );
        WorkerCandidateReference hotRef = reference("hot");
        when(mechanism.observePreEpochHot(
                eq("group-1"), eq(1_000L), any(), eq(100)
        )).thenReturn(new WorkerSweepPage(
                List.of(hotRef),
                mock(WorkerSweepCursor.class)
        ));
        WorkerServiceabilityObservation hot = observation(
                "hot",
                "adapter-1",
                ServiceabilityPolarity.HOT,
                100,
                0,
                hotRef
        );
        when(mechanism.recheck("group-1", List.of(hotRef)))
                .thenReturn(List.of(hot));
        when(mechanism.holdForProbe(List.of(hot)))
                .thenReturn(List.of(hot));
        WorkerServiceabilityRuntime serviceability = mock(
                WorkerServiceabilityRuntime.class
        );
        when(serviceability.offerProbeRequests(
                "adapter-1",
                List.of("hot")
        )).thenReturn(Map.of(
                "hot",
                ProbeRequestOfferStatus.CAPACITY
        ));
        WorkerServiceabilityDispatchPolicy policy = policy(
                mechanism,
                serviceability
        );

        assertEquals(0, policy.dispatchProbes(
                List.of("group-1"),
                config(100),
                1_000
        ));

        InOrder order = inOrder(mechanism, serviceability);
        order.verify(mechanism).holdForProbe(List.of(hot));
        order.verify(serviceability).offerProbeRequests(
                "adapter-1",
                List.of("hot")
        );
        verify(mechanism).coldPark(List.of(), 5);
    }

    private static WorkerServiceabilityDispatchPolicy policy(
            WorkerServiceabilityDispatchMechanism mechanism,
            WorkerServiceabilityRuntime serviceability
    ) {
        return new WorkerServiceabilityDispatchPolicy(
                mechanism,
                serviceability,
                () -> 10_000
        );
    }

    private static WorkerServiceabilityRuntime serviceability(
            String adapterId,
            String workerId
    ) {
        WorkerServiceabilityRuntime serviceability = mock(
                WorkerServiceabilityRuntime.class
        );
        when(serviceability.offerProbeRequests(
                adapterId,
                List.of(workerId)
        )).thenReturn(Map.of(
                workerId,
                ProbeRequestOfferStatus.OFFERED
        ));
        return serviceability;
    }

    private static WorkerServiceabilityDispatchConfig config(
            long recoveryIntervalMillis
    ) {
        return new WorkerServiceabilityDispatchConfig(
                recoveryIntervalMillis,
                10_000,
                5,
                List.of("system-polling")
        );
    }

    private static WorkerSweepPage emptyPage() {
        return new WorkerSweepPage(
                List.of(),
                WorkerSweepCursor.start()
        );
    }

    private static WorkerSweepPage page(
            WorkerCandidateReference reference
    ) {
        return new WorkerSweepPage(
                List.of(reference),
                mock(WorkerSweepCursor.class)
        );
    }

    private static WorkerCandidateReference reference(String workerId) {
        return reference("group-1", workerId);
    }

    private static WorkerCandidateReference reference(
            String workerGroupId,
            String workerId
    ) {
        WorkerCandidateReference reference = mock(
                WorkerCandidateReference.class
        );
        when(reference.workerId()).thenReturn(workerId);
        when(reference.workerGroupId()).thenReturn(workerGroupId);
        return reference;
    }

    private static WorkerServiceabilityObservation observation(
            String workerId,
            String endpoint,
            ServiceabilityPolarity polarity,
            long timeMillis,
            int laneRank,
            WorkerCandidateReference reference
    ) {
        return new WorkerServiceabilityObservation(
                "group-1",
                workerId,
                polarity,
                timeMillis,
                laneRank,
                endpoint,
                reference
        );
    }

    private static WorkerServiceabilityObservation observation(
            String workerGroupId,
            String workerId,
            String endpoint,
            WorkerCandidateReference reference
    ) {
        return new WorkerServiceabilityObservation(
                workerGroupId,
                workerId,
                ServiceabilityPolarity.HOT,
                100,
                0,
                endpoint,
                reference
        );
    }

}
