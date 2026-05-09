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
        dto.setSharedConfig(request.getSharedConfig());
        dto.setExecutionSpec(request.getExecutionSpec());
        dto.setSourceType(request.getSourceType());
        dto.setSourceRef(request.getSourceRef());
        return dto;
    }
}
