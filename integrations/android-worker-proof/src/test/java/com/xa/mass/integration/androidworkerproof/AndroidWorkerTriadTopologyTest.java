package com.xa.mass.integration.androidworkerproof;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AndroidWorkerTriadTopologyTest {

    @Test
    void fixesThreeUniqueApplicationAndHostAddresses() {
        assertEquals(3, AndroidWorkerTriadTopology.WORKERS.size());
        assertEquals(Set.of(
                "com.xa.mass.integration.androidworker.lab1",
                "com.xa.mass.integration.androidworker.lab2",
                "com.xa.mass.integration.androidworker.lab3"
        ), AndroidWorkerTriadTopology.applicationIds());
        assertEquals(Set.of(
                URI.create("http://127.0.0.1:18184"),
                URI.create("http://127.0.0.1:18185"),
                URI.create("http://127.0.0.1:18186")
        ), AndroidWorkerTriadTopology.WORKERS.stream()
                .map(AndroidWorkerTriadTopology.WorkerAddress::deviceBaseUrl)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        assertEquals(
                "com.xa.mass.integration.androidworker.lab2",
                AndroidWorkerTriadTopology.OUTAGE_TARGET.applicationId()
        );
    }

    @Test
    void targetsOneApplicationThroughWorkerProperties() {
        assertEquals(
                Map.of(
                        "workerId",
                        Map.of("$eq", "worker-lab1"),
                        "worker.packageName",
                        Map.of(
                                "$eq",
                                "com.xa.mass.integration.androidworker.lab1"
                        )
                ),
                AndroidWorkerTriadTopology.allocationRule(
                        AndroidWorkerTriadTopology.WORKERS.get(0),
                        "worker-lab1"
                )
        );
    }
}
