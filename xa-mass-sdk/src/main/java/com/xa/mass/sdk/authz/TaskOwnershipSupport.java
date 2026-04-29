package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;

import java.util.Objects;

/**
 * Shared helper for applying framework-owned task ownership stamps.
 */
public final class TaskOwnershipSupport {

    private TaskOwnershipSupport() {
    }

    public static MassTaskCreateRequest stamp(MassTaskCreateRequest request, PrincipalContext principal) {
        Objects.requireNonNull(request, "request");
        TaskOwnershipStamp stamp = TaskOwnershipStamp.fromPrincipal(Objects.requireNonNull(principal, "principal"));
        return MassTaskCreateRequest.builder()
                .userId(request.getUserId())
                .project(request.getProject())
                .taskName(request.getTaskName())
                .sharedConfig(applyStamp(request.getSharedConfig(), stamp))
                .inputs(request.getInputs())
                .batchSize(request.getBatchSize())
                .defaultMsgMaxRetryCount(request.getDefaultMsgMaxRetryCount())
                .openEnded(request.isOpenEnded())
                .maxRuntimeSeconds(request.getMaxRuntimeSeconds())
                .sourceType(request.getSourceType())
                .workloadClass(request.getWorkloadClass())
                .sourceRef(request.getSourceRef())
                .build();
    }

    public static MassTaskRequest stamp(MassTaskRequest request, PrincipalContext principal) {
        Objects.requireNonNull(request, "request");
        TaskOwnershipStamp stamp = TaskOwnershipStamp.fromPrincipal(Objects.requireNonNull(principal, "principal"));
        return MassTaskRequest.builder()
                .userId(request.getUserId())
                .project(request.getProject())
                .taskName(request.getTaskName())
                .eventCode(request.getEventCode())
                .mode(request.getMode())
                .payloadType(request.getPayloadType())
                .sharedConfig(applyStamp(request.getSharedConfig(), stamp))
                .inputs(request.getInputs())
                .batchSize(request.getBatchSize())
                .defaultMsgMaxRetryCount(request.getDefaultMsgMaxRetryCount())
                .maxRuntimeSeconds(request.getMaxRuntimeSeconds())
                .sourceType(request.getSourceType())
                .workloadClass(request.getWorkloadClass())
                .sourceRef(request.getSourceRef())
                .build();
    }

    private static java.util.Map<String, Object> applyStamp(java.util.Map<String, Object> sharedConfig,
                                                            TaskOwnershipStamp stamp) {
        if (TaskOwnershipStamp.fromSharedConfig(sharedConfig) != null) {
            return sharedConfig;
        }
        return TaskOwnershipStamp.applyToSharedConfig(sharedConfig, stamp);
    }
}
