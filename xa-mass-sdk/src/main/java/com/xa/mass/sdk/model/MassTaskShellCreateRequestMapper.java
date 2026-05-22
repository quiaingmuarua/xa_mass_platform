package com.xa.mass.sdk.model;

import com.xa.mass.base.model.TaskShellCreateRequestDto;

import java.util.Objects;

public final class MassTaskShellCreateRequestMapper {

    private MassTaskShellCreateRequestMapper() {
    }

    public static TaskShellCreateRequestDto toEngineRequest(MassTaskShellCreateRequest request) {
        Objects.requireNonNull(request, "request");
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setUserId(request.getUserId());
        dto.setTenantId(request.getTenantId());
        dto.setProject(request.getProject());
        dto.setContract(parseContract(request.getContract()));
        dto.setSharedConfig(request.getSharedConfig());
        dto.setExecutionSpec(toEngineExecutionSpec(request.getExecutionSpec()));
        dto.setSourceRef(request.getSourceRef());
        return dto;
    }

    private static com.xa.mass.base.model.TaskExecutionSpec toEngineExecutionSpec(TaskExecutionOptions requestSpec) {
        com.xa.mass.base.model.TaskExecutionSpec spec = new com.xa.mass.base.model.TaskExecutionSpec();
        if (requestSpec == null) {
            return spec;
        }
        spec.setProfile(parseProfile(requestSpec.getProfile()));
        spec.setWorkloadClass(parseWorkloadClass(requestSpec.getWorkloadClass()));
        spec.setBatchSize(requestSpec.getBatchSize());
        spec.setMaxRuntimeSeconds(requestSpec.getMaxRuntimeSeconds());
        spec.setDefaultMaxRetryCount(requestSpec.getDefaultMaxRetryCount());
        spec.setForeground(requestSpec.isForeground());
        return spec;
    }

    private static com.xa.mass.base.enums.task.TaskContract parseContract(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return com.xa.mass.base.enums.task.TaskContract.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static com.xa.mass.base.enums.task.TaskExecutionProfile parseProfile(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return com.xa.mass.base.enums.task.TaskExecutionProfile.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static com.xa.mass.base.enums.task.TaskWorkloadClass parseWorkloadClass(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return com.xa.mass.base.enums.task.TaskWorkloadClass.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
