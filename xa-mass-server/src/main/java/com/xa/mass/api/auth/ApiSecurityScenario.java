package com.xa.mass.api.auth;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.AuthorizationDecision;
import com.xa.mass.sdk.authz.AuthorizationReasonCode;
import com.xa.mass.sdk.authz.PlatformAction;
import com.xa.mass.sdk.authz.PlatformResourceType;

public enum ApiSecurityScenario {
    TASK_API_KEY_TASK_CREATE(
            "task-create",
            PlatformResourceType.TASK,
            PlatformAction.CREATE,
            PrincipalContext.TASK_CREATE_PERMISSION,
            CredentialAudience.TASK_API_KEY
    ),
    TASK_API_KEY_TASK_VIEW(
            "task-view",
            PlatformResourceType.TASK,
            PlatformAction.VIEW,
            null,
            CredentialAudience.TASK_API_KEY
    ),
    TASK_API_KEY_TASK_APPEND(
            "task-append",
            PlatformResourceType.TASK,
            PlatformAction.EDIT,
            PrincipalContext.TASK_CREATE_PERMISSION,
            CredentialAudience.TASK_API_KEY
    ),
    WORKER_REGISTER(
            "worker-register",
            PlatformResourceType.WORKER,
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
    ),
    WORKER_REPORT_HANDLER_EVIDENCE(
            "worker-report-handler-evidence",
            PlatformResourceType.WORKER,
            PlatformAction.POLL,
            PrincipalContext.EXTERNAL_WORKER_PERMISSION,
            CredentialAudience.EXTERNAL_WORKER
    ),
    WORKER_REPORT_RUNTIME_EVIDENCE(
            "worker-report-runtime-evidence",
            PlatformResourceType.WORKER,
            PlatformAction.POLL,
            PrincipalContext.EXTERNAL_WORKER_PERMISSION,
            CredentialAudience.EXTERNAL_WORKER
    ),
    WORKER_ACK_COMMAND(
            "worker-ack-command",
            PlatformResourceType.WORKER,
            PlatformAction.POLL,
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
            case TASK_API_KEY -> "Invalid or missing API-key credential";
            case EXTERNAL_WORKER -> "Invalid or missing worker credential";
        };
    }

    public String deniedMessage(AuthorizationDecision decision) {
        if (decision == null || decision.getReason() == null || decision.getReason().isBlank()) {
            return credentialAudience == CredentialAudience.TASK_API_KEY ? "API-key credential authorization denied" : "Worker credential authorization denied";
        }
        AuthorizationReasonCode reasonCode = decision.getReasonCode();
        String reason = decision.getReason();
        if (reasonCode == AuthorizationReasonCode.PERMISSION_DENIED) {
            return credentialAudience == CredentialAudience.TASK_API_KEY ? "API-key credential permission denied: " + suffixAfter(reason, "permission denied: ") : "Worker credential permission denied: " + suffixAfter(reason, "permission denied: ");
        }
        if (reasonCode == AuthorizationReasonCode.PROJECT_SCOPE_DENIED) {
            return credentialAudience == CredentialAudience.TASK_API_KEY ? "API-key credential project scope denied: " + suffixAfter(reason, "project scope denied: ") : "Worker credential project scope denied: " + suffixAfter(reason, "project scope denied: ");
        }
        if (reasonCode == AuthorizationReasonCode.EVENT_SCOPE_DENIED) {
            return credentialAudience == CredentialAudience.TASK_API_KEY ? "API-key credential event scope denied: " + suffixAfter(reason, "event scope denied: ") : "Worker credential event scope denied: " + suffixAfter(reason, "event scope denied: ");
        }
        if (credentialAudience == CredentialAudience.TASK_API_KEY
                && reasonCode == AuthorizationReasonCode.USER_SCOPE_DENIED) {
            return "API-key credential user scope denied: " + suffixAfter(reason, "user scope denied: ");
        }
        if (credentialAudience == CredentialAudience.TASK_API_KEY
                && reasonCode == AuthorizationReasonCode.OWNERSHIP_STAMP_MISSING) {
            return "Task is missing ownership metadata";
        }
        if (credentialAudience == CredentialAudience.TASK_API_KEY
                && reasonCode == AuthorizationReasonCode.OWNER_MISMATCH) {
            return "API-key credential owner mismatch: " + suffixAfter(reason, "task owner mismatch: ");
        }
        if (credentialAudience == CredentialAudience.EXTERNAL_WORKER
                && reasonCode == AuthorizationReasonCode.WORKER_BINDING_MISSING) {
            return "Worker credential is missing workerId binding";
        }
        if (credentialAudience == CredentialAudience.EXTERNAL_WORKER
                && reasonCode == AuthorizationReasonCode.WORKER_BINDING_DENIED) {
            return "Worker credential binding denied: " + suffixAfter(reason, "worker binding denied: ");
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
        TASK_API_KEY,
        EXTERNAL_WORKER
    }
}
