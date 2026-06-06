package com.xa.mass.api.auth;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.session.ApiKeyViewerSessionService;
import com.xa.mass.api.auth.usage.ApiUsageLedgerService;
import com.xa.mass.api.auth.usage.ApiUsageOperation;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.AuthorizationDecision;
import com.xa.mass.sdk.authz.AuthorizationPolicy;
import com.xa.mass.sdk.authz.AuthorizationReasonCode;
import com.xa.mass.sdk.authz.AuthorizationRequest;
import com.xa.mass.sdk.authz.DefaultAuthorizationPolicy;
import com.xa.mass.sdk.authz.PlatformAction;
import com.xa.mass.sdk.authz.PlatformResourceType;
import com.xa.mass.sdk.authz.TaskOwnershipSupport;
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
    private ApiUsageLedgerService apiUsageLedgerService;
    private ApiKeyCredentialService apiKeyCredentialService;
    private ApiKeyViewerSessionService apiKeyViewerSessionService;

    public ApiAuthorizationService() {
        this(null, new DefaultAuthorizationPolicy());
    }

    @Autowired
    public ApiAuthorizationService(AuthProvider authProvider, AuthorizationPolicy authorizationPolicy) {
        this.authProvider = authProvider;
        this.authorizationPolicy = authorizationPolicy == null ? new DefaultAuthorizationPolicy() : authorizationPolicy;
    }

    @Autowired(required = false)
    public void setApiUsageLedgerService(ApiUsageLedgerService apiUsageLedgerService) {
        this.apiUsageLedgerService = apiUsageLedgerService;
    }

    @Autowired(required = false)
    public void setApiKeyCredentialService(ApiKeyCredentialService apiKeyCredentialService) {
        this.apiKeyCredentialService = apiKeyCredentialService;
    }

    @Autowired(required = false)
    public void setApiKeyViewerSessionService(ApiKeyViewerSessionService apiKeyViewerSessionService) {
        this.apiKeyViewerSessionService = apiKeyViewerSessionService;
    }

    public PrincipalContext resolveApiKeyPrincipal(String apiKeyHeader,
                                                String authorizationHeader,
                                                ApiSecurityScenario scenario,
                                                Map<String, Object> context) {
        if (!SdkCredentialAuthSupport.hasCredentialAttempt(apiKeyHeader, authorizationHeader)) {
            return null;
        }
        PrincipalContext apiKeyPrincipal = authenticateApiKeyOrViewerSession(apiKeyHeader, authorizationHeader);
        if (apiKeyPrincipal == null) {
            logCredentialFailure(scenario.surface(), scenario.unauthenticatedMessage(), context);
            throw new ApiUnauthenticatedException(scenario.unauthenticatedMessage());
        }
        return apiKeyPrincipal;
    }

    public AuthorizedApiKeyTaskCreate resolveAuthorizedApiKeyTaskCreate(String apiKeyHeader,
                                                                              String authorizationHeader,
                                                                              String requestedProject,
                                                                              String eventCode,
                                                                              String requestedUserId,
                                                                              Map<String, Object> context) {
        PrincipalContext apiKeyPrincipal =
                resolveApiKeyPrincipal(apiKeyHeader, authorizationHeader, ApiSecurityScenario.TASK_API_KEY_TASK_CREATE, context);
        if (apiKeyPrincipal == null) {
            return null;
        }
        requireApiKeyTaskCreate(
                apiKeyPrincipal,
                requestedProject,
                eventCode,
                requestedUserId,
                ApiSecurityScenario.TASK_API_KEY_TASK_CREATE,
                context
        );
        return new AuthorizedApiKeyTaskCreate(
                apiKeyPrincipal,
                resolveApiKeyProject(requestedProject, apiKeyPrincipal),
                resolveApiKeyUserId(requestedUserId, apiKeyPrincipal)
        );
    }

    public PrincipalContext requireExternalWorkerCredential(String apiKeyHeader,
                                                            String authorizationHeader,
                                                            ApiSecurityScenario scenario,
                                                            Map<String, Object> context) {
        PrincipalContext apiKeyPrincipal = authenticateApiKeyPrincipal(apiKeyHeader, authorizationHeader);
        if (apiKeyPrincipal == null) {
            logCredentialFailure(scenario.surface(), scenario.unauthenticatedMessage(), context);
            throw new ApiUnauthenticatedException(scenario.unauthenticatedMessage());
        }
        return apiKeyPrincipal;
    }

    public PrincipalContext requireAuthorizedWorkerCredential(String apiKeyHeader,
                                                              String authorizationHeader,
                                                              ApiSecurityScenario scenario,
                                                              String workerId,
                                                              String project,
                                                              List<WorkerEventBinding> eventBindings,
                                                              Map<String, Object> context) {
        PrincipalContext apiKeyPrincipal = requireExternalWorkerCredential(apiKeyHeader, authorizationHeader, scenario, context);
        requireWorkerAccess(apiKeyPrincipal, scenario, workerId, project, eventBindings, context);
        return apiKeyPrincipal;
    }

    public PrincipalContext resolveAuthorizedTaskViewer(String apiKeyHeader,
                                                        String authorizationHeader,
                                                        String taskId,
                                                        String project,
                                                        Map<String, Object> sharedConfig,
                                                        Map<String, Object> context) {
        PrincipalContext apiKeyPrincipal =
                resolveApiKeyPrincipal(apiKeyHeader, authorizationHeader, ApiSecurityScenario.TASK_API_KEY_TASK_VIEW, context);
        if (apiKeyPrincipal == null) {
            return null;
        }
        requireTaskOwnershipAccess(apiKeyPrincipal, taskId, project, sharedConfig, ApiSecurityScenario.TASK_API_KEY_TASK_VIEW, context);
        return apiKeyPrincipal;
    }

    public PrincipalContext resolveTaskViewerCredential(String apiKeyHeader,
                                                        String authorizationHeader,
                                                        Map<String, Object> context) {
        return resolveApiKeyPrincipal(apiKeyHeader, authorizationHeader, ApiSecurityScenario.TASK_API_KEY_TASK_VIEW, context);
    }

    public PrincipalContext resolveAuthorizedTaskAppender(String apiKeyHeader,
                                                          String authorizationHeader,
                                                          String taskId,
                                                          String project,
                                                          Map<String, Object> sharedConfig,
                                                          List<String> eventCodes,
                                                          Map<String, Object> context) {
        PrincipalContext apiKeyPrincipal =
                resolveApiKeyPrincipal(apiKeyHeader, authorizationHeader, ApiSecurityScenario.TASK_API_KEY_TASK_APPEND, context);
        if (apiKeyPrincipal == null) {
            return null;
        }
        requireTaskOwnershipAccess(apiKeyPrincipal, taskId, project, sharedConfig, ApiSecurityScenario.TASK_API_KEY_TASK_APPEND, context);
        List<String> normalizedEventCodes = eventCodes == null ? List.of() : List.copyOf(eventCodes);
        for (String eventCode : normalizedEventCodes) {
            requireApiKeyTaskAccess(
                    apiKeyPrincipal,
                    project,
                    eventCode,
                    null,
                    ApiSecurityScenario.TASK_API_KEY_TASK_APPEND,
                    context
            );
        }
        return apiKeyPrincipal;
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
            logDenied(surface, principal, resourceType, action, null, null, null,
                    resourceAttributes, decision.getReasonCode(), decision.getReason(), context);
            throw new ApiForbiddenException(decision.getReason());
        }
    }

    public void requireApiKeyTaskCreate(PrincipalContext principal,
                                           String project,
                                           String eventCode,
                                           String userId,
                                           ApiSecurityScenario scenario,
                                           Map<String, Object> context) {
        requireApiKeyTaskAccess(principal, project, eventCode, userId, scenario, context);
    }

    private void requireApiKeyTaskAccess(PrincipalContext principal,
                                            String project,
                                            String eventCode,
                                            String userId,
                                            ApiSecurityScenario scenario,
                                            Map<String, Object> context) {
        Map<String, Object> resourceAttributes = new LinkedHashMap<>();
        if (scenario.requiredPermission() != null && !scenario.requiredPermission().isBlank()) {
            resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, scenario.requiredPermission());
        }
        if (userId != null) {
            resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_USER_ID, userId);
        }
        AuthorizationDecision decision = authorize(
                principal,
                scenario.resourceType(),
                scenario.action(),
                project,
                eventCode,
                null,
                resourceAttributes
        );
        if (!decision.isAllowed()) {
            logDenied(scenario.surface(), principal, scenario.resourceType(), scenario.action(),
                    project, eventCode, null, resourceAttributes, decision.getReasonCode(), decision.getReason(), context);
            recordRejectedUsage(principal, context, project, eventCode);
            throw new SecurityException(scenario.deniedMessage(decision));
        }
    }

    public void requireWorkerAccess(PrincipalContext principal,
                                    ApiSecurityScenario scenario,
                                    String workerId,
                                    String project,
                                    List<WorkerEventBinding> eventBindings,
                                    Map<String, Object> context) {
        Map<String, Object> resourceAttributes = new LinkedHashMap<>();
        resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, scenario.requiredPermission());
        if (eventBindings != null && !eventBindings.isEmpty()) {
            resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_EVENT_BINDINGS, eventBindings);
        }
        AuthorizationDecision decision = authorize(
                principal,
                scenario.resourceType(),
                scenario.action(),
                project,
                null,
                workerId,
                resourceAttributes
        );
        if (!decision.isAllowed()) {
            logDenied(scenario.surface(), principal, scenario.resourceType(), scenario.action(),
                    project, null, workerId, resourceAttributes, decision.getReasonCode(), decision.getReason(), context);
            throw new ApiForbiddenException(scenario.deniedMessage(decision));
        }
    }

    public void requireTaskOwnershipAccess(PrincipalContext principal,
                                           String taskId,
                                           String project,
                                           Map<String, Object> sharedConfig,
                                           ApiSecurityScenario scenario,
                                           Map<String, Object> context) {
        Map<String, Object> resourceAttributes = taskOwnershipAttributes(taskId, project, sharedConfig);
        AuthorizationDecision decision = TaskOwnershipSupport.authorizeOwnership(
                principal,
                sharedConfig
        );
        if (!decision.isAllowed()) {
            logDenied(scenario.surface(), principal, scenario.resourceType(), scenario.action(),
                    project,
                    null,
                    null,
                    resourceAttributes,
                    decision.getReasonCode(),
                    decision.getReason(),
                    context);
            recordRejectedUsage(principal, context, project, null);
            throw new ApiForbiddenException(scenario.deniedMessage(decision));
        }
    }

    public boolean allowsTaskOwnershipAccess(PrincipalContext principal,
                                             Map<String, Object> sharedConfig) {
        return TaskOwnershipSupport.authorizeOwnership(
                principal,
                sharedConfig
        ).isAllowed();
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

    private PrincipalContext authenticateApiKeyOrViewerSession(String apiKeyHeader, String authorizationHeader) {
        String credential = SdkCredentialAuthSupport.extractCredential(apiKeyHeader, authorizationHeader);
        PrincipalContext principal = authenticateApiKeyPrincipal(credential);
        if (principal != null) {
            return principal;
        }
        return apiKeyViewerSessionService == null ? null : apiKeyViewerSessionService.authenticate(credential);
    }

    private PrincipalContext authenticateApiKeyPrincipal(String apiKeyHeader, String authorizationHeader) {
        String credential = SdkCredentialAuthSupport.extractCredential(apiKeyHeader, authorizationHeader);
        return authenticateApiKeyPrincipal(credential);
    }

    private PrincipalContext authenticateApiKeyPrincipal(String credential) {
        if (credential == null) {
            return null;
        }
        PrincipalContext principal = authProvider == null ? null : authProvider.authenticate(credential);
        if (apiKeyCredentialService != null) {
            principal = apiKeyCredentialService.validateAuthenticatedPrincipal(principal);
        }
        return principal;
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
                           AuthorizationReasonCode reasonCode,
                           String reason,
                           Map<String, Object> context) {
        logger.warn(
                "Authorization denied: surface={} principalId={} principalType={} resourceType={} action={} project={} eventCode={} workerId={} reasonCode={} reason={} resourceAttributes={} context={}",
                surface,
                principal != null ? principal.getPrincipalId() : "anonymous",
                principal != null ? principal.getPrincipalType() : "UNKNOWN",
                resourceType,
                action,
                project,
                eventCode,
                workerId,
                reasonCode,
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

    private void recordRejectedUsage(PrincipalContext principal,
                                     Map<String, Object> context,
                                     String project,
                                     String eventCode) {
        if (apiUsageLedgerService == null) {
            return;
        }
        ApiUsageOperation operation = apiUsageLedgerService.operationFromContext(context);
        if (operation == null) {
            return;
        }
        apiUsageLedgerService.recordRejected(
                principal,
                operation,
                project,
                eventCode,
                apiUsageLedgerService.stringFromContext(context, "taskId"),
                apiUsageLedgerService.stringFromContext(context, ApiUsageLedgerService.CONTEXT_USAGE_MESSAGE_ID),
                apiUsageLedgerService.stringFromContext(context, ApiUsageLedgerService.CONTEXT_USAGE_REQUEST_ID)
        );
    }

    private Map<String, Object> taskOwnershipAttributes(String taskId,
                                                        String project,
                                                        Map<String, Object> sharedConfig) {
        if (taskId == null && project == null && (sharedConfig == null || sharedConfig.isEmpty())) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (taskId != null) {
            attributes.put("taskId", taskId);
        }
        if (project != null) {
            attributes.put("project", project);
        }
        com.xa.mass.sdk.authz.TaskOwnershipStamp stamp =
                com.xa.mass.sdk.authz.TaskOwnershipStamp.fromSharedConfig(sharedConfig);
        if (stamp != null) {
            attributes.put("ownerPrincipalId", stamp.getCreatedByPrincipalId());
            attributes.put("ownerPrincipalType", stamp.getCreatedByPrincipalType().name());
        }
        return Map.copyOf(attributes);
    }

    private String resolveApiKeyProject(String requestedProject, PrincipalContext apiKeyPrincipal) {
        String normalizedRequestedProject = SdkCredentialAuthSupport.firstNonBlank(requestedProject);
        String scopedProject = SdkCredentialAuthSupport.firstNonBlank(apiKeyPrincipal.getProjectScope());
        if (scopedProject != null) {
            return scopedProject;
        }
        if (normalizedRequestedProject == null
                && apiKeyPrincipal.getProjectScopes().size() == 1
                && !PrincipalContext.WILDCARD_SCOPE.equals(apiKeyPrincipal.getProjectScopes().get(0))) {
            return apiKeyPrincipal.getProjectScopes().get(0);
        }
        if (normalizedRequestedProject != null) {
            return normalizedRequestedProject;
        }
        throw new IllegalArgumentException("project is required when apiKeyPrincipal has no project scope");
    }

    private String resolveApiKeyUserId(String requestedUserId, PrincipalContext apiKeyPrincipal) {
        String normalizedRequestedUserId = SdkCredentialAuthSupport.firstNonBlank(requestedUserId);
        String scopedUserId = SdkCredentialAuthSupport.firstNonBlank(apiKeyPrincipal.getUserId());
        if (scopedUserId != null) {
            return requireUserId(scopedUserId);
        }
        if (normalizedRequestedUserId != null) {
            return requireUserId(normalizedRequestedUserId);
        }
        return requireUserId(apiKeyPrincipal.getPrincipalId());
    }

    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        return userId.trim();
    }

    public record AuthorizedApiKeyTaskCreate(PrincipalContext principal,
                                                String project,
                                                String userId) {
    }
}
