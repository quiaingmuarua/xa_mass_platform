package com.xa.mass.sdk.model;

import com.xa.mass.engine.model.TaskCreateRequestDto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared mapper from SDK-native task requests to the current engine DTO.
 */
public final class MassTaskRequestMapper {

    private MassTaskRequestMapper() {
    }

    public static TaskCreateRequestDto toEngineRequest(MassTaskRequest request) {
        Objects.requireNonNull(request, "request");
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setUserId(request.getUserId());
        dto.setProject(request.getProject());
        dto.setTaskName(request.getTaskName());
        dto.setSharedConfig(withSdkMetadata(request));
        dto.setInputs(request.toEngineInputs());
        dto.setBatchSize(request.getBatchSize());
        dto.setDefaultMsgMaxRetryCount(request.getDefaultMsgMaxRetryCount());
        dto.setOpenEnded(request.isStreaming());
        dto.setMaxRuntimeSeconds(request.getMaxRuntimeSeconds());
        dto.setSourceType(request.getSourceType());
        dto.setSourceRef(request.getSourceRef());
        return dto;
    }

    public static Map<String, Object> withSdkMetadata(MassTaskRequest request) {
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
