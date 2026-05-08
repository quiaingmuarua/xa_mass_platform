package com.xa.mass.sdk.model;

import com.xa.mass.base.model.TaskShellCreateRequestDto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MassTaskShellCreateRequestMapper {

    private MassTaskShellCreateRequestMapper() {
    }

    public static TaskShellCreateRequestDto toEngineRequest(MassTaskShellCreateRequest request) {
        Objects.requireNonNull(request, "request");
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setUserId(request.getUserId());
        dto.setProject(request.getProject());
        dto.setTaskName(request.getTaskName());
        dto.setSharedConfig(withSdkMetadata(request));
        dto.setBatchSize(request.getBatchSize());
        dto.setMaxRuntimeSeconds(request.getMaxRuntimeSeconds());
        dto.setSourceType(request.getSourceType());
        dto.setWorkloadClass(request.getWorkloadClass());
        dto.setSourceRef(request.getSourceRef());
        return dto;
    }

    public static Map<String, Object> withSdkMetadata(MassTaskShellCreateRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> merged = new LinkedHashMap<>(request.getSharedConfig());
        Map<String, Object> sdkMetadata = new LinkedHashMap<>();
        if (request.getEventCode() != null && !request.getEventCode().isBlank()) {
            sdkMetadata.put("eventCode", request.getEventCode());
        }
        sdkMetadata.put("payloadType", request.getPayloadType().name());
        sdkMetadata.put("taskMode", request.getMode().name());
        if (!sdkMetadata.isEmpty()) {
            merged.put("_sdk", Map.copyOf(sdkMetadata));
        }
        return Map.copyOf(merged);
    }
}
