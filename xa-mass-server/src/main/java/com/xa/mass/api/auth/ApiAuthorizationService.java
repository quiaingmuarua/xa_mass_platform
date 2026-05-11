package com.xa.mass.api.auth;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
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
                                                ApiSecurityScenario scenario,
                                                Map<String, Object> context) {
        if (!SdkCredentialAuthSupport.hasCredentialAttempt(apiKeyHeader, authorizationHeader)) {
            return null;
        }
        PrincipalContext submitter =
                SdkCredentialAuthSupport.authenticate(authProvider, apiKeyHeader, authorizationHeader);
        if (submitter == null) {
            logCredentialFailure(scenario.surface(), scenario.unauthenticatedMessage(), context);
            throw new ApiUnauthenticatedException(scenario.unauthenticatedMessage());
        }
        return submitter;
    }

    public AuthorizedSubmitterTaskCreate resolveAuthorizedSubmitterTaskCreate(String apiKeyHeader,
                                                                              String authorizationHeader,
                                                                              String requestedProject,
                                                                              String eventCode,
                                                                              String requestedUserId,
                                                                              Map<String, Object> context) {
        PrincipalContext submitter =
                resolveSdkSubmitter(apiKeyHeader, authorizationHeader, ApiSecurityScenario.SUBMITTER_TASK_CREATE, context);
        if (submitter == null) {
            return null;
        }
        requireSubmitterTaskCreate(
                submitter,
                requestedProject,
                eventCode,
                requestedUserId,
                ApiSecurityScenario.SUBMITTER_TASK_CREATE,
                context
        );
        return new AuthorizedSubmitterTaskCreate(
                submitter,
                resolveSubmitterProject(requestedProject, submitter),
                resolveSubmitterUserId(requestedUserId, submitter)
        );
    }

    public PrincipalContext requireExternalWorkerCredential(String apiKeyHeader,
                                                            String authorizationHeader,
                                                            ApiSecurityScenario scenario,
                                                            Map<String, Object> context) {
        PrincipalContext submitter =
                SdkCredentialAuthSupport.authenticate(authProvider, apiKeyHeader, authorizationHeader);
        if (submitter == null) {
            logCredentialFailure(scenario.surface(), scenario.unauthenticatedMessage(), context);
            throw new ApiUnauthenticatedException(scenario.unauthenticatedMessage());
        }
        return submitter;
    }

    public PrincipalContext requireAuthorizedWorkerCredential(String apiKeyHeader,
                                                              String authorizationHeader,
                                                              ApiSecurityScenario scenario,
                                                              String workerId,
                                                              String project,
                                                              List<WorkerEventBinding> eventBindings,
                                                              Map<String, Object> context) {
        PrincipalContext submitter = requireExternalWorkerCredential(apiKeyHeader, authorizationHeader, scenario, context);
        requireWorkerAccess(submitter, scenario, workerId, project, eventBindings, context);
        return submitter;
    }

    public PrincipalContext resolveAuthorizedTaskViewer(String apiKeyHeader,
                                                        String authorizationHeader,
                                                        String taskId,
                                                        String project,
                                                        Map<String, Object> sharedConfig,
                                                        Map<String, Object> context) {
        PrincipalContext submitter =
                resolveSdkSubmitter(apiKeyHeader, authorizationHeader, ApiSecurityScenario.SUBMITTER_TASK_VIEW, context);
        if (submitter == null) {
            return null;
        }
        requireTaskOwnershipAccess(submitter, taskId, project, sharedConfig, ApiSecurityScenario.SUBMITTER_TASK_VIEW, context);
        return submitter;
    }

    public PrincipalContext resolveTaskViewerCredential(String apiKeyHeader,
                                                        String authorizationHeader,
                                                        Map<String, Object> context) {
        return resolveSdkSubmitter(apiKeyHeader, authorizationHeader, ApiSecurityScenario.SUBMITTER_TASK_VIEW, context);
    }

    public PrincipalContext resolveAuthorizedTaskAppender(String apiKeyHeader,
                                                          String authorizationHeader,
                                                          String taskId,
                                                          String project,
                                                          Map<String, Object> sharedConfig,
                                                          List<String> eventCodes,
                                                          Map<String, Object> context) {
        PrincipalContext submitter =
                resolveSdkSubmitter(apiKeyHeader, authorizationHeader, ApiSecurityScenario.SUBMITTER_TASK_APPEND, context);
        if (submitter == null) {
            return null;
        }
        requireTaskOwnershipAccess(submitter, taskId, project, sharedConfig, ApiSecurityScenario.SUBMITTER_TASK_APPEND, context);
        List<String> normalizedEventCodes = eventCodes == null ? List.of() : List.copyOf(eventCodes);
        for (String eventCode : normalizedEventCodes) {
            requireSubmitterTaskAccess(
                    submitter,
                    project,
                    eventCode,
                    null,
                    ApiSecurityScenario.SUBMITTER_TASK_APPEND,
                    context
            );
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
            logDenied(surface, principal, resourceType, action, null, null, null,
                    resourceAttributes, decision.getReasonCode(), decision.getReason(), context);
            throw new ApiForbiddenException(decision.getReason());
        }
    }

    public void requireSubmitterTaskCreate(PrincipalContext principal,
                                           String project,
                                           String eventCode,
                                           String userId,
                                           ApiSecurityScenario scenario,
                                           Map<String, Object> context) {
        requireSubmitterTaskAccess(principal, project, eventCode, userId, scenario, context);
    }

    private void requireSubmitterTaskAccess(PrincipalContext principal,
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

    private String resolveSubmitterProject(String requestedProject, PrincipalContext submitter) {
        String normalizedRequestedProject = SdkCredentialAuthSupport.firstNonBlank(requestedProject);
        String scopedProject = SdkCredentialAuthSupport.firstNonBlank(submitter.getProjectScope());
        if (scopedProject != null) {
            return scopedProject;
        }
        if (normalizedRequestedProject == null
                && submitter.getProjectScopes().size() == 1
                && !PrincipalContext.WILDCARD_SCOPE.equals(submitter.getProjectScopes().get(0))) {
            return submitter.getProjectScopes().get(0);
        }
        if (normalizedRequestedProject != null) {
            return normalizedRequestedProject;
        }
        throw new IllegalArgumentException("project is required when submitter has no project scope");
    }

    private String resolveSubmitterUserId(String requestedUserId, PrincipalContext submitter) {
        String normalizedRequestedUserId = SdkCredentialAuthSupport.firstNonBlank(requestedUserId);
        String scopedUserId = SdkCredentialAuthSupport.firstNonBlank(submitter.getUserId());
        if (scopedUserId != null) {
            return requireUserId(scopedUserId);
        }
        if (normalizedRequestedUserId != null) {
            return requireUserId(normalizedRequestedUserId);
        }
        return requireUserId(submitter.getPrincipalId());
    }

    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        return userId.trim();
    }

    public record AuthorizedSubmitterTaskCreate(PrincipalContext principal,
                                                String project,
                                                String userId) {
    }
}
