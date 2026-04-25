package com.xa.mass.sdk.model;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.transport.WorkerTransportHints;

import java.util.*;

/**
 * SDK-to-runtime mapper with SDK-level validation and normalization.
 */
public final class SdkResourceMapper {

    private SdkResourceMapper() {
    }

    public static TaskCreateRequestDto toEngineRequest(MassTaskCreateRequest request) {
        Objects.requireNonNull(request, "request");
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setUserId(request.getUserId());
        dto.setProject(request.getProject());
        dto.setTaskName(request.getTaskName());
        dto.setSharedConfig(request.getSharedConfig());
        dto.setInputs(request.getInputs());
        dto.setBatchSize(request.getBatchSize());
        dto.setDefaultMsgMaxRetryCount(request.getDefaultMsgMaxRetryCount());
        dto.setOpenEnded(request.isOpenEnded());
        dto.setMaxRuntimeSeconds(request.getMaxRuntimeSeconds());
        dto.setSourceType(request.getSourceType());
        dto.setSourceRef(request.getSourceRef());
        return dto;
    }

    public static Worker toWorker(WorkerRegistration request) {
        Objects.requireNonNull(request, "request");
        String workerId = requireNonBlank(request.getWorkerId(), "workerId");
        String transportHint = WorkerTransportHints.normalize(requireNonBlank(request.getTransportHint(), "transportHint"));
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(blankToNull(request.getWorkerGroupId()));
        worker.setSupportedProjects(normalizedList(request.getSupportedProjects()));
        worker.setSupportedEventCodes(normalizedList(request.getSupportedEventCodes()));
        worker.setOnlineStrategy(transportHint);
        worker.setAttributes(normalizedAttributes(request.getAttributes()));
        return worker;
    }

    public static WorkerContext toWorkerContext(WorkerContextRegistration request) {
        Objects.requireNonNull(request, "request");
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId(requireNonBlank(request.getWorkerContextId(), "workerContextId"));
        workerContext.setWorkerId(requireNonBlank(request.getWorkerId(), "workerId"));
        String project = blankToNull(request.getProject());
        if (project != null) {
            workerContext.setProject(project);
        }
        workerContext.setRoutingTags(normalizedRoutingTags(request.getRoutingTags()));
        workerContext.setAttributes(normalizedAttributes(request.getAttributes()));
        return workerContext;
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

    private static List<String> normalizedList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String normalizedValue = blankToNull(value);
            if (normalizedValue != null) {
                normalized.add(normalizedValue);
            }
        }
        return normalized.isEmpty() ? Collections.emptyList() : List.copyOf(normalized);
    }

    private static Set<String> normalizedRoutingTags(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String normalizedValue = blankToNull(value);
            if (normalizedValue != null) {
                normalized.add(normalizedValue.toLowerCase(Locale.ROOT));
            }
        }
        return normalized.isEmpty() ? Collections.emptySet() : Set.copyOf(normalized);
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
