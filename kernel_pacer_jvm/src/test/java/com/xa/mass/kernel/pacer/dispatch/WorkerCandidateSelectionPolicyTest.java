package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.LeaseMode;
import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.WorkerCandidateObservation;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WorkerCandidateSelectionPolicyTest {

    @Test
    void leasesOnlyPreselectedMatchesAndRematchesCanonicalDescriptor() {
        WorkerCandidateMechanism mechanism = mock(
                WorkerCandidateMechanism.class
        );
        WorkerCandidateObservation east = worker("east", "east");
        WorkerCandidateObservation west = worker("west", "west");
        WorkerCandidateObservation leasedEast = worker("east", "east");
        when(mechanism.observeHot("group-1", null, 100))
                .thenReturn(List.of(east, west));
        when(mechanism.leaseSelected(
                eq("group-1"),
                eq(List.of(east)),
                eq(5_000L),
                eq(LeaseMode.ACQUIRE)
        )).thenReturn(List.of(leasedEast));
        WorkerCandidateSelectionPolicy policy =
                new WorkerCandidateSelectionPolicy(
                        mechanism,
                        new WorkerCandidateMatcher(),
                        100,
                        null
                );

        Map<String, List<WorkerCandidateObservation>> acquired =
                policy.acquireHotPoolCandidates(
                        "group-1",
                        Map.of("candidate", new WorkerCandidateRequest(
                                0,
                                1,
                                Map.of(
                                        "worker.region",
                                        Map.of("$eq", "east")
                                )
                        )),
                        5_000L
                );

        assertEquals(List.of(leasedEast), acquired.get("candidate"));
        verify(mechanism).leaseSelected(
                "group-1",
                List.of(east),
                5_000L,
                LeaseMode.ACQUIRE
        );
    }

    @Test
    void postLeaseDescriptorChangeFailsClosedWithoutRefill() {
        WorkerCandidateMechanism mechanism = mock(
                WorkerCandidateMechanism.class
        );
        WorkerCandidateObservation east = worker("east", "east");
        WorkerCandidateObservation leasedWest = worker("east", "west");
        when(mechanism.observeHot("group-1", null, 100))
                .thenReturn(List.of(east));
        when(mechanism.leaseSelected(
                "group-1",
                List.of(east),
                5_000L,
                LeaseMode.ACQUIRE
        )).thenReturn(List.of(leasedWest));
        WorkerCandidateSelectionPolicy policy =
                new WorkerCandidateSelectionPolicy(
                        mechanism,
                        new WorkerCandidateMatcher(),
                        100,
                        null
                );

        Map<String, List<WorkerCandidateObservation>> acquired =
                policy.acquireHotPoolCandidates(
                        "group-1",
                        Map.of("candidate", new WorkerCandidateRequest(
                                0,
                                1,
                                Map.of(
                                        "worker.region",
                                        Map.of("$eq", "east")
                                )
                        )),
                        5_000L
                );

        assertEquals(List.of(), acquired.get("candidate"));
    }

    @Test
    void directSelectionLeasesAtMostOneHundredUniqueWorkersPerRound() {
        WorkerCandidateMechanism mechanism = mock(
                WorkerCandidateMechanism.class
        );
        List<WorkerCandidateObservation> broad = IntStream.range(0, 100)
                .mapToObj(index -> worker("broad-" + index, "east"))
                .toList();
        WorkerCandidateObservation explicit = worker("explicit", "east");
        when(mechanism.observeHot("group-1", null, 100))
                .thenReturn(broad);
        when(mechanism.observeExplicit(
                "group-1",
                List.of("explicit"),
                null
        )).thenReturn(List.of(explicit));
        when(mechanism.leaseSelected(
                anyString(),
                anyList(),
                eq(5_000L),
                eq(LeaseMode.ACQUIRE)
        )).thenAnswer(invocation -> invocation.getArgument(1));
        WorkerCandidateSelectionPolicy policy =
                new WorkerCandidateSelectionPolicy(
                        mechanism,
                        new WorkerCandidateMatcher(),
                        100,
                        null
                );
        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        requests.put("broad", new WorkerCandidateRequest(
                0,
                100,
                Map.of()
        ));
        requests.put("explicit", new WorkerCandidateRequest(
                1,
                1,
                Map.of("workerId", Map.of("$eq", "explicit"))
        ));

        Map<String, List<WorkerCandidateObservation>> acquired =
                policy.acquireWorkerCandidates(
                        WorkerCandidateAcquisitionStrategy.DIRECT,
                        "group-1",
                        requests,
                        5_000L
                );

        assertEquals(100, acquired.get("broad").size());
        assertEquals(List.of(), acquired.get("explicit"));
        verify(mechanism).leaseSelected(
                eq("group-1"),
                argThat(workers -> workers.size() == 100
                        && workers.stream().noneMatch(worker ->
                        "explicit".equals(worker.workerId()))),
                eq(5_000L),
                eq(LeaseMode.ACQUIRE)
        );
    }

    private static WorkerCandidateObservation worker(
            String workerId,
            String region
    ) {
        return new WorkerCandidateObservation(
                workerId,
                "group-1",
                new WorkerDescriptor(
                        workerId,
                        "group-1",
                        "adapter-1",
                        Map.of("region", region),
                        Map.of()
                ),
                mock(WorkerCandidateReference.class)
        );
    }
}
