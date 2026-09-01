package com.xa.mass.integration.androidworkerproof;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AndroidWorkerTriadTopology {

    static final List<WorkerAddress> WORKERS = List.of(
            new WorkerAddress(
                    "lab1",
                    "com.xa.mass.integration.androidworker.lab1",
                    URI.create("http://127.0.0.1:18184")
            ),
            new WorkerAddress(
                    "lab2",
                    "com.xa.mass.integration.androidworker.lab2",
                    URI.create("http://127.0.0.1:18185")
            ),
            new WorkerAddress(
                    "lab3",
                    "com.xa.mass.integration.androidworker.lab3",
                    URI.create("http://127.0.0.1:18186")
            )
    );

    static final WorkerAddress OUTAGE_TARGET = WORKERS.get(1);

    static {
        Set<String> variants = new LinkedHashSet<>();
        Set<String> applicationIds = new LinkedHashSet<>();
        Set<URI> deviceBaseUrls = new LinkedHashSet<>();
        for (WorkerAddress worker : WORKERS) {
            if (!variants.add(worker.variant())
                    || !applicationIds.add(worker.applicationId())
                    || !deviceBaseUrls.add(worker.deviceBaseUrl())) {
                throw new IllegalStateException(
                        "Android Worker triad topology must be unique"
                );
            }
        }
    }

    private AndroidWorkerTriadTopology() {
    }

    static Set<String> applicationIds() {
        return WORKERS.stream()
                .map(WorkerAddress::applicationId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static Map<String, Object> allocationRule(
            WorkerAddress worker,
            String workerId
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
        return Map.of(
                "workerId",
                Map.of("$eq", workerId),
                "worker.packageName",
                Map.of("$eq", worker.applicationId())
        );
    }

    record WorkerAddress(
            String variant,
            String applicationId,
            URI deviceBaseUrl
    ) {
        WorkerAddress {
            variant = requireText(variant, "variant");
            applicationId = requireText(applicationId, "applicationId");
            if (deviceBaseUrl == null
                    || deviceBaseUrl.getHost() == null
                    || !"http".equalsIgnoreCase(deviceBaseUrl.getScheme())
                    || deviceBaseUrl.getPort() <= 0) {
                throw new IllegalArgumentException(
                        "deviceBaseUrl must be an absolute HTTP URI with a port"
                );
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
