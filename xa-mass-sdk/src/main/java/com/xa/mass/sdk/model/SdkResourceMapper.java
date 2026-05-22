package com.xa.mass.sdk.model;

import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.model.Worker;
import com.xa.mass.transport.WorkerTransportHints;

import java.util.*;

/**
 * SDK-to-runtime mapper with SDK-level validation and normalization.
 */
public final class SdkResourceMapper {

    private SdkResourceMapper() {
    }

    public static TaskShellCreateRequestDto toEngineRequest(MassTaskShellCreateRequest request) {
        return MassTaskShellCreateRequestMapper.toEngineRequest(request);
    }

    public static Worker toWorker(WorkerRegistration request) {
        Objects.requireNonNull(request, "request");
        String workerId = requireNonBlank(request.getWorkerId(), "workerId");
        String adapterId = requireNonBlank(request.getAdapterId(), "adapterId");
        String transportHint = WorkerTransportHints.normalize(requireNonBlank(request.getTransportHint(), "transportHint"));
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setAdapterNodeId(blankToNull(request.getAdapterNodeId()));
        worker.setWorkerGroupId(blankToNull(request.getWorkerGroupId()));
        worker.setSupportedProjects(Collections.emptyList());
        worker.setSupportedEventCodes(Collections.emptyList());
        worker.setAdapterId(adapterId);
        worker.setOnlineStrategy(transportHint);
        worker.setMaxConcurrentWork(request.getMaxConcurrentWork());
        worker.setAttributes(normalizedAttributes(request.getAttributes()));
        return worker;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Map<String, String> normalizedAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = blankToNull(entry.getKey());
            String value = entry.getValue();
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        }
        return normalized.isEmpty() ? Collections.emptyMap() : Map.copyOf(normalized);
    }
}
