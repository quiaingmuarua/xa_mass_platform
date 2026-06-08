package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.model.WorkerEventBinding;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default platform authorization policy that preserves the current server/SDK behavior.
 */
public final class DefaultAuthorizationPolicy implements AuthorizationPolicy {

    public static final String ATTR_REQUIRED_PERMISSION = "requiredPermission";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_BOUND_WORKER_ID = "workerId";
    public static final String ATTR_EVENT_BINDINGS = "eventBindings";

    @Override
    public AuthorizationDecision authorize(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        PrincipalContext principal = request.getPrincipal();
        if (principal == null) {
            return AuthorizationDecision.deny(AuthorizationReasonCode.PRINCIPAL_REQUIRED, "principal is required");
        }

        String requiredPermission = request.getStringAttribute(ATTR_REQUIRED_PERMISSION);
        if (requiredPermission != null && !principal.hasPermission(requiredPermission)) {
            return AuthorizationDecision.deny(AuthorizationReasonCode.PERMISSION_DENIED,
                    "permission denied: " + requiredPermission);
        }

        return switch (request.getResourceType()) {
            case TASK -> authorizeTask(request, principal);
            case WORKER -> authorizeWorker(request, principal);
            case RULE, CONFIG, USER, ROLE, API_KEY, API_USAGE -> AuthorizationDecision.allow();
        };
    }

    private AuthorizationDecision authorizeTask(AuthorizationRequest request, PrincipalContext principal) {
        if (request.getAction() == PlatformAction.CREATE) {
            AuthorizationDecision projectDecision = authorizeTaskProjectScope(request, principal);
            if (!projectDecision.isAllowed()) {
                return projectDecision;
            }
            AuthorizationDecision eventDecision = authorizeTaskEventScope(request, principal);
            if (!eventDecision.isAllowed()) {
                return eventDecision;
            }
            return authorizeTaskUserScope(request, principal);
        }
        if (request.getAction() == PlatformAction.EDIT) {
            AuthorizationDecision projectDecision = authorizeTaskProjectScope(request, principal);
            if (!projectDecision.isAllowed()) {
                return projectDecision;
            }
            return authorizeTaskEventScope(request, principal);
        }
        return AuthorizationDecision.allow();
    }

    private AuthorizationDecision authorizeWorker(AuthorizationRequest request, PrincipalContext principal) {
        AuthorizationDecision workerBindingDecision = authorizeWorkerBinding(request, principal);
        if (!workerBindingDecision.isAllowed()) {
            return workerBindingDecision;
        }
        if (request.getAction() == PlatformAction.REGISTER) {
            Object rawBindings = request.getResourceAttributes().get(ATTR_EVENT_BINDINGS);
            if (rawBindings instanceof List<?> bindings) {
                for (Object item : bindings) {
                    if (!(item instanceof WorkerEventBinding binding)) {
                        continue;
                    }
                    if (!principal.allowsEvent(binding.getEventCode())) {
                        return AuthorizationDecision.deny(AuthorizationReasonCode.EVENT_SCOPE_DENIED,
                                "event scope denied: " + binding.getEventCode());
                    }
                    if (binding.getProjectCodes() == null) {
                        continue;
                    }
                    for (String projectCode : binding.getProjectCodes()) {
                        if (projectCode != null && !principal.allowsProject(projectCode)) {
                            return AuthorizationDecision.deny(AuthorizationReasonCode.PROJECT_SCOPE_DENIED,
                                    "project scope denied: " + projectCode);
                        }
                    }
                }
            }
        }
        return AuthorizationDecision.allow();
    }

    private AuthorizationDecision authorizeTaskProjectScope(AuthorizationRequest request, PrincipalContext principal) {
        String requestedProject = request.getProject();
        String scopedProject = firstNonBlank(principal.getProjectScope());
        if (scopedProject != null) {
            if (requestedProject != null && !scopedProject.equals(requestedProject)) {
                return AuthorizationDecision.deny(AuthorizationReasonCode.PROJECT_SCOPE_DENIED,
                        "project scope denied: " + requestedProject);
            }
            return AuthorizationDecision.allow();
        }
        if (requestedProject != null && !principal.allowsProject(requestedProject)) {
            return AuthorizationDecision.deny(AuthorizationReasonCode.PROJECT_SCOPE_DENIED,
                    "project scope denied: " + requestedProject);
        }
        return AuthorizationDecision.allow();
    }

    private AuthorizationDecision authorizeTaskEventScope(AuthorizationRequest request, PrincipalContext principal) {
        String eventCode = request.getEventCode();
        if (eventCode != null && !principal.allowsEvent(eventCode)) {
            return AuthorizationDecision.deny(AuthorizationReasonCode.EVENT_SCOPE_DENIED,
                    "event scope denied: " + eventCode);
        }
        return AuthorizationDecision.allow();
    }

    private AuthorizationDecision authorizeTaskUserScope(AuthorizationRequest request, PrincipalContext principal) {
        String requestedUserId = firstNonBlank(request.getStringAttribute(ATTR_USER_ID));
        String scopedUserId = firstNonBlank(principal.getUserId());
        if (scopedUserId != null && requestedUserId != null && !scopedUserId.equals(requestedUserId)) {
            return AuthorizationDecision.deny(AuthorizationReasonCode.USER_SCOPE_DENIED,
                    "user scope denied: " + requestedUserId);
        }
        return AuthorizationDecision.allow();
    }

    private AuthorizationDecision authorizeWorkerBinding(AuthorizationRequest request, PrincipalContext principal) {
        String requestedWorkerId = firstNonBlank(request.getWorkerId());
        String boundWorkerId = firstNonBlank(principal.getAttributes().get(ATTR_BOUND_WORKER_ID));
        if (requestedWorkerId == null) {
            return AuthorizationDecision.allow();
        }
        if (boundWorkerId == null) {
            return AuthorizationDecision.deny(AuthorizationReasonCode.WORKER_BINDING_MISSING,
                    "worker binding missing");
        }
        if (!requestedWorkerId.equals(boundWorkerId)) {
            return AuthorizationDecision.deny(AuthorizationReasonCode.WORKER_BINDING_DENIED,
                    "worker binding denied: " + requestedWorkerId);
        }
        return AuthorizationDecision.allow();
    }

    private String firstNonBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
