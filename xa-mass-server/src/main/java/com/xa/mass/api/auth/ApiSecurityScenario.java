package com.xa.mass.api.auth;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.AuthorizationDecision;
import com.xa.mass.sdk.authz.AuthorizationReasonCode;
import com.xa.mass.sdk.authz.PlatformAction;
import com.xa.mass.sdk.authz.PlatformResourceType;

public enum ApiSecurityScenario {
    SUBMITTER_TASK_CREATE(
            "task-create",
            PlatformResourceType.TASK,
            PlatformAction.CREATE,
            PrincipalContext.TASK_CREATE_PERMISSION,
            CredentialAudience.SDK_SUBMITTER
    ),
    SUBMITTER_TASK_VIEW(
            "task-view",
            PlatformResourceType.TASK,
            PlatformAction.VIEW,
            null,
            CredentialAudience.SDK_SUBMITTER
    ),
    SUBMITTER_TASK_APPEND(
            "task-append",
            PlatformResourceType.TASK,
            PlatformAction.EDIT,
            PrincipalContext.TASK_CREATE_PERMISSION,
            CredentialAudience.SDK_SUBMITTER
    ),
    WORKER_REGISTER(
            "worker-register",
            PlatformResourceType.WORKER,
            PlatformAction.REGISTER,
            PrincipalContext.EXTERNAL_WORKER_PERMISSION,
            CredentialAudience.EXTERNAL_WORKER
    ),
    WORKER_CONTEXT_REGISTER(
            "worker-context-register",
            PlatformResourceType.WORKER_CONTEXT,
            PlatformAction.REGISTER,
            PrincipalContext.EXTERNAL_WORKER_PERMISSION,
            CredentialAudience.EXTERNAL_WORKER
    ),
    WORKER_ONLINE(
            "worker-online",
            PlatformResourceType.WORKER,
            PlatformAction.POLL,
            PrincipalContext.EXTERNAL_WORKER_PERMISSION,
            CredentialAudience.EXTERNAL_WORKER
    ),
    WORKER_HEARTBEAT(
            "worker-heartbeat",
            PlatformResourceType.WORKER,
            PlatformAction.POLL,
            PrincipalContext.EXTERNAL_WORKER_PERMISSION,
            CredentialAudience.EXTERNAL_WORKER
    ),
    WORKER_OFFLINE(
            "worker-offline",
            PlatformResourceType.WORKER,
            PlatformAction.POLL,
            PrincipalContext.EXTERNAL_WORKER_PERMISSION,
            CredentialAudience.EXTERNAL_WORKER
    ),
    WORKER_POLL(
            "worker-poll",
            PlatformResourceType.WORKER,
            PlatformAction.POLL,
            PrincipalContext.EXTERNAL_WORKER_PERMISSION,
            CredentialAudience.EXTERNAL_WORKER
    ),
    WORKER_SUBMIT_RESULT(
            "worker-submit-result",
            PlatformResourceType.WORKER,
            PlatformAction.REPORT_RESULT,
            PrincipalContext.EXTERNAL_WORKER_PERMISSION,
            CredentialAudience.EXTERNAL_WORKER
    );

    private final String surface;
    private final PlatformResourceType resourceType;
    private final PlatformAction action;
    private final String requiredPermission;
    private final CredentialAudience credentialAudience;

    ApiSecurityScenario(String surface,
                        PlatformResourceType resourceType,
                        PlatformAction action,
                        String requiredPermission,
                        CredentialAudience credentialAudience) {
        this.surface = surface;
        this.resourceType = resourceType;
        this.action = action;
        this.requiredPermission = requiredPermission;
        this.credentialAudience = credentialAudience;
    }

    public String surface() {
        return surface;
    }

    public PlatformResourceType resourceType() {
        return resourceType;
    }

    public PlatformAction action() {
        return action;
    }

    public String requiredPermission() {
        return requiredPermission;
    }

    public String unauthenticatedMessage() {
        return switch (credentialAudience) {
            case SDK_SUBMITTER -> "Invalid or missing SDK credential";
            case EXTERNAL_WORKER -> "Invalid or missing worker credential";
        };
    }

    public String deniedMessage(AuthorizationDecision decision) {
        if (decision == null || decision.getReason() == null || decision.getReason().isBlank()) {
            return "SDK credential authorization denied";
        }
        AuthorizationReasonCode reasonCode = decision.getReasonCode();
        String reason = decision.getReason();
        if (reasonCode == AuthorizationReasonCode.PERMISSION_DENIED) {
            return "SDK credential permission denied: " + suffixAfter(reason, "permission denied: ");
        }
        if (reasonCode == AuthorizationReasonCode.PROJECT_SCOPE_DENIED) {
            return "SDK credential project scope denied: " + suffixAfter(reason, "project scope denied: ");
        }
        if (reasonCode == AuthorizationReasonCode.EVENT_SCOPE_DENIED) {
            return "SDK credential event scope denied: " + suffixAfter(reason, "event scope denied: ");
        }
        if (credentialAudience == CredentialAudience.SDK_SUBMITTER
                && reasonCode == AuthorizationReasonCode.USER_SCOPE_DENIED) {
            return "SDK credential user scope denied: " + suffixAfter(reason, "user scope denied: ");
        }
        if (credentialAudience == CredentialAudience.SDK_SUBMITTER
                && reasonCode == AuthorizationReasonCode.OWNERSHIP_STAMP_MISSING) {
            return "Task is missing ownership metadata";
        }
        if (credentialAudience == CredentialAudience.SDK_SUBMITTER
                && reasonCode == AuthorizationReasonCode.OWNER_MISMATCH) {
            return "SDK credential owner mismatch: " + suffixAfter(reason, "task owner mismatch: ");
        }
        if (credentialAudience == CredentialAudience.EXTERNAL_WORKER
                && reasonCode == AuthorizationReasonCode.WORKER_BINDING_MISSING) {
            return "SDK credential is missing workerId binding";
        }
        if (credentialAudience == CredentialAudience.EXTERNAL_WORKER
                && reasonCode == AuthorizationReasonCode.WORKER_BINDING_DENIED) {
            return "SDK credential worker binding denied: " + suffixAfter(reason, "worker binding denied: ");
        }
        return reason;
    }

    private String suffixAfter(String value, String prefix) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.startsWith(prefix)) {
            return value.substring(prefix.length());
        }
        return value;
    }

    private enum CredentialAudience {
        SDK_SUBMITTER,
        EXTERNAL_WORKER
    }
}
