package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;

import java.util.Map;
import java.util.Objects;

/**
 * Shared helper for applying framework-owned task ownership stamps.
 */
public final class TaskOwnershipSupport {

    private TaskOwnershipSupport() {
    }

    public static MassTaskShellCreateRequest stamp(MassTaskShellCreateRequest request, PrincipalContext principal) {
        Objects.requireNonNull(request, "request");
        TaskOwnershipStamp stamp = TaskOwnershipStamp.fromPrincipal(Objects.requireNonNull(principal, "principal"));
        return MassTaskShellCreateRequest.builder()
                .userId(request.getUserId())
                .project(request.getProject())
                .taskName(request.getTaskName())
                .eventCode(request.getEventCode())
                .mode(request.getMode())
                .payloadType(request.getPayloadType())
                .sharedConfig(applyStamp(request.getSharedConfig(), stamp))
                .batchSize(request.getBatchSize())
                .maxRuntimeSeconds(request.getMaxRuntimeSeconds())
                .sourceType(request.getSourceType())
                .workloadClass(request.getWorkloadClass())
                .sourceRef(request.getSourceRef())
                .build();
    }

    public static AuthorizationDecision authorizeOwnership(PrincipalContext principal,
                                                           Map<String, Object> sharedConfig) {
        if (principal == null) {
            return AuthorizationDecision.deny(AuthorizationReasonCode.PRINCIPAL_REQUIRED, "principal is required");
        }
        TaskOwnershipStamp stamp = TaskOwnershipStamp.fromSharedConfig(sharedConfig);
        if (stamp == null) {
            return AuthorizationDecision.deny(AuthorizationReasonCode.OWNERSHIP_STAMP_MISSING,
                    "task ownership stamp missing");
        }
        if (!matchesPrincipal(stamp, principal)) {
            return AuthorizationDecision.deny(AuthorizationReasonCode.OWNER_MISMATCH,
                    "task owner mismatch: " + stamp.getCreatedByPrincipalId());
        }
        return AuthorizationDecision.allow();
    }

    public static boolean matchesPrincipal(TaskOwnershipStamp stamp, PrincipalContext principal) {
        if (stamp == null || principal == null) {
            return false;
        }
        return Objects.equals(stamp.getCreatedByPrincipalId(), principal.getPrincipalId())
                && stamp.getCreatedByPrincipalType() == principal.getPrincipalType();
    }

    private static java.util.Map<String, Object> applyStamp(java.util.Map<String, Object> sharedConfig,
                                                            TaskOwnershipStamp stamp) {
        if (TaskOwnershipStamp.fromSharedConfig(sharedConfig) != null) {
            return sharedConfig;
        }
        return TaskOwnershipStamp.applyToSharedConfig(sharedConfig, stamp);
    }
}
