package com.xa.mass.api.auth;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.AuthorizationDecision;
import com.xa.mass.sdk.authz.AuthorizationPolicy;
import com.xa.mass.sdk.authz.AuthorizationRequest;
import com.xa.mass.sdk.authz.DefaultAuthorizationPolicy;
import com.xa.mass.sdk.authz.PlatformAction;
import com.xa.mass.sdk.authz.PlatformResourceType;
import com.xa.mass.sdk.model.WorkerEventBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ApiAuthorizationService {

    private static final Logger logger = LoggerFactory.getLogger(ApiAuthorizationService.class);

    private final AuthProvider authProvider;
    private final AuthorizationPolicy authorizationPolicy;

    public ApiAuthorizationService() {
        this(null, new DefaultAuthorizationPolicy());
    }

    @Autowired
    public ApiAuthorizationService(AuthProvider authProvider, AuthorizationPolicy authorizationPolicy) {
        this.authProvider = authProvider;
        this.authorizationPolicy = authorizationPolicy == null ? new DefaultAuthorizationPolicy() : authorizationPolicy;
    }

    public PrincipalContext resolveSdkSubmitter(String apiKeyHeader,
                                                String authorizationHeader,
                                                String surface,
                                                Map<String, Object> context) {
        if (!SdkCredentialAuthSupport.hasCredentialAttempt(apiKeyHeader, authorizationHeader)) {
            return null;
        }
        PrincipalContext submitter =
                SdkCredentialAuthSupport.authenticate(authProvider, apiKeyHeader, authorizationHeader);
        if (submitter == null) {
            logCredentialFailure(surface, "Invalid or missing SDK credential", context);
            throw new ApiUnauthenticatedException("Invalid or missing SDK credential");
        }
        return submitter;
    }

    public PrincipalContext requireExternalWorkerCredential(String apiKeyHeader,
                                                            String authorizationHeader,
                                                            String surface,
                                                            Map<String, Object> context) {
        PrincipalContext submitter =
                SdkCredentialAuthSupport.authenticate(authProvider, apiKeyHeader, authorizationHeader);
        if (submitter == null) {
            logCredentialFailure(surface, "Invalid or missing worker credential", context);
            throw new ApiUnauthenticatedException("Invalid or missing worker credential");
        }
        return submitter;
    }

    public void requireOperatorRoutePermission(PrincipalContext principal,
                                               PlatformResourceType resourceType,
                                               PlatformAction action,
                                               String requiredPermission,
                                               String surface,
                                               Map<String, Object> context) {
        Map<String, Object> resourceAttributes = Map.of(
                DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION,
                requiredPermission
        );
        AuthorizationDecision decision = authorize(principal, resourceType, action, null, null, null, resourceAttributes);
        if (!decision.isAllowed()) {
            logDenied(surface, principal, resourceType, action, null, null, null, resourceAttributes, decision.getReason(), context);
            throw new ApiForbiddenException(decision.getReason());
        }
    }

    public void requireSubmitterTaskCreate(PrincipalContext principal,
                                           String project,
                                           String eventCode,
                                           String userId,
                                           String surface,
                                           Map<String, Object> context) {
        Map<String, Object> resourceAttributes = new LinkedHashMap<>();
        resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, PrincipalContext.TASK_CREATE_PERMISSION);
        if (userId != null) {
            resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_USER_ID, userId);
        }
        AuthorizationDecision decision = authorize(
                principal,
                PlatformResourceType.TASK,
                PlatformAction.CREATE,
                project,
                eventCode,
                null,
                resourceAttributes
        );
        if (!decision.isAllowed()) {
            logDenied(surface, principal, PlatformResourceType.TASK, PlatformAction.CREATE,
                    project, eventCode, null, resourceAttributes, decision.getReason(), context);
            throw new SecurityException(toSdkCredentialMessage(decision.getReason()));
        }
    }

    public void requireWorkerAccess(PrincipalContext principal,
                                    PlatformResourceType resourceType,
                                    PlatformAction action,
                                    String workerId,
                                    String project,
                                    List<WorkerEventBinding> eventBindings,
                                    String surface,
                                    Map<String, Object> context) {
        Map<String, Object> resourceAttributes = new LinkedHashMap<>();
        resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, PrincipalContext.EXTERNAL_WORKER_PERMISSION);
        if (eventBindings != null && !eventBindings.isEmpty()) {
            resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_EVENT_BINDINGS, eventBindings);
        }
        AuthorizationDecision decision = authorize(
                principal,
                resourceType,
                action,
                project,
                null,
                workerId,
                resourceAttributes
        );
        if (!decision.isAllowed()) {
            logDenied(surface, principal, resourceType, action,
                    project, null, workerId, resourceAttributes, decision.getReason(), context);
            throw new ApiForbiddenException(toWorkerCredentialMessage(decision.getReason()));
        }
    }

    private AuthorizationDecision authorize(PrincipalContext principal,
                                            PlatformResourceType resourceType,
                                            PlatformAction action,
                                            String project,
                                            String eventCode,
                                            String workerId,
                                            Map<String, Object> resourceAttributes) {
        return authorizationPolicy.authorize(AuthorizationRequest.builder()
                .principal(principal)
                .resourceType(resourceType)
                .action(action)
                .project(project)
                .eventCode(eventCode)
                .workerId(workerId)
                .resourceAttributes(resourceAttributes)
                .build());
    }

    private void logCredentialFailure(String surface, String reason, Map<String, Object> context) {
        logger.warn("Authentication failed: surface={} reason={} context={}", surface, reason, safeContext(context));
    }

    private void logDenied(String surface,
                           PrincipalContext principal,
                           PlatformResourceType resourceType,
                           PlatformAction action,
                           String project,
                           String eventCode,
                           String workerId,
                           Map<String, Object> resourceAttributes,
                           String reason,
                           Map<String, Object> context) {
        logger.warn(
                "Authorization denied: surface={} principalId={} principalType={} resourceType={} action={} project={} eventCode={} workerId={} reason={} resourceAttributes={} context={}",
                surface,
                principal != null ? principal.getPrincipalId() : "anonymous",
                principal != null ? principal.getPrincipalType() : "UNKNOWN",
                resourceType,
                action,
                project,
                eventCode,
                workerId,
                reason,
                resourceAttributes,
                safeContext(context)
        );
    }

    private Map<String, Object> safeContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(context);
    }

    private String toSdkCredentialMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return "SDK credential authorization denied";
        }
        if (reason.startsWith("permission denied: ")) {
            return "SDK credential permission denied: " + reason.substring("permission denied: ".length());
        }
        if (reason.startsWith("project scope denied: ")) {
            return "SDK credential project scope denied: " + reason.substring("project scope denied: ".length());
        }
        if (reason.startsWith("user scope denied: ")) {
            return "SDK credential user scope denied: " + reason.substring("user scope denied: ".length());
        }
        if (reason.startsWith("event scope denied: ")) {
            return "SDK credential event scope denied: " + reason.substring("event scope denied: ".length());
        }
        return reason;
    }

    private String toWorkerCredentialMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return "SDK credential authorization denied";
        }
        if (reason.startsWith("permission denied: ")) {
            return "SDK credential permission denied: " + reason.substring("permission denied: ".length());
        }
        if (reason.equals("worker binding missing")) {
            return "SDK credential is missing workerId binding";
        }
        if (reason.startsWith("worker binding denied: ")) {
            return "SDK credential worker binding denied: " + reason.substring("worker binding denied: ".length());
        }
        if (reason.startsWith("project scope denied: ")) {
            return "SDK credential project scope denied: " + reason.substring("project scope denied: ".length());
        }
        if (reason.startsWith("event scope denied: ")) {
            return "SDK credential event scope denied: " + reason.substring("event scope denied: ".length());
        }
        return reason;
    }
}
