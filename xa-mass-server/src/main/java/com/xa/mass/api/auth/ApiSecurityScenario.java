package com.xa.mass.api.auth;

import com.xa.mass.sdk.auth.PrincipalContext;
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

    public String deniedMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return "SDK credential authorization denied";
        }
        if (reason.startsWith("permission denied: ")) {
            return "SDK credential permission denied: " + reason.substring("permission denied: ".length());
        }
        if (reason.startsWith("project scope denied: ")) {
            return "SDK credential project scope denied: " + reason.substring("project scope denied: ".length());
        }
        if (reason.startsWith("event scope denied: ")) {
            return "SDK credential event scope denied: " + reason.substring("event scope denied: ".length());
        }
        if (credentialAudience == CredentialAudience.SDK_SUBMITTER
                && reason.startsWith("user scope denied: ")) {
            return "SDK credential user scope denied: " + reason.substring("user scope denied: ".length());
        }
        if (credentialAudience == CredentialAudience.EXTERNAL_WORKER
                && reason.equals("worker binding missing")) {
            return "SDK credential is missing workerId binding";
        }
        if (credentialAudience == CredentialAudience.EXTERNAL_WORKER
                && reason.startsWith("worker binding denied: ")) {
            return "SDK credential worker binding denied: " + reason.substring("worker binding denied: ".length());
        }
        return reason;
    }

    private enum CredentialAudience {
        SDK_SUBMITTER,
        EXTERNAL_WORKER
    }
}
