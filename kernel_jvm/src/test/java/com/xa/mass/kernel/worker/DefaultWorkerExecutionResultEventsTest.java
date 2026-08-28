package com.xa.mass.kernel.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.xa.mass.kernel.score.WorkerScoreCore;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultWorkerExecutionResultEventsTest {

    @Test
    void mapsNamedExecutionEventsToTheCurrentBatchScoreMechanisms() {
        List<String> calls = new ArrayList<>();
        WorkerScoreCore scores = proxy(
                WorkerScoreCore.class,
                (_proxy, method, args) -> {
                    if (method.getName().equals(
                            "releaseCompletedHotScoreHolds"
                    )) {
                        calls.add("succeeded:" + args[0] + ":" + args[1]
                                + ":" + args[2]);
                        return Map.of();
                    }
                    if (method.getName().equals("releaseScoreHolds")) {
                        calls.add("failed:" + args[0] + ":" + args[1]
                                + ":" + args[2]);
                        return Map.of();
                    }
                    throw new AssertionError(
                            "Unexpected Worker score operation: "
                                    + method.getName()
                    );
                }
        );
        DefaultWorkerExecutionResultEvents events =
                new DefaultWorkerExecutionResultEvents(scores);
        LinkedHashMap<String, WorkerLeaseReference> leases =
                new LinkedHashMap<>();
        leases.put(
                "worker-1",
                WorkerLeaseReference.fromEncodedScore(11)
        );
        leases.put(
                "worker-2",
                WorkerLeaseReference.fromEncodedScore(12)
        );

        events.onTaskSucceeded("group-1", leases, 1_000);
        events.onTaskFailed("group-1", leases, 1_001);

        assertEquals(List.of(
                "succeeded:group-1:{worker-1=11, worker-2=12}:1000",
                "failed:group-1:{worker-1=11, worker-2=12}:1001"
        ), calls);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(
            Class<T> contract,
            java.lang.reflect.InvocationHandler handler
    ) {
        return (T) Proxy.newProxyInstance(
                contract.getClassLoader(),
                new Class<?>[]{contract},
                handler
        );
    }
}
