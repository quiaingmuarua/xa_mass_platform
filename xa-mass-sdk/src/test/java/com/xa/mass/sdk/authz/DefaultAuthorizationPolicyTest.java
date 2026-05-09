package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.model.WorkerEventBinding;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAuthorizationPolicyTest {

    private final DefaultAuthorizationPolicy policy = new DefaultAuthorizationPolicy();

    @Test
    void operatorPermissionAuthorizationUsesRequiredPermission() {
        PrincipalContext operator = PrincipalContext.builder()
                .principalId("ops-viewer")
                .permissions(List.of("task:view"))
                .build();

        AuthorizationDecision allowed = policy.authorize(AuthorizationRequest.builder()
                .principal(operator)
                .resourceType(PlatformResourceType.TASK)
                .action(PlatformAction.VIEW)
                .resourceAttributes(Map.of(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, "task:view"))
                .build());

        AuthorizationDecision denied = policy.authorize(AuthorizationRequest.builder()
                .principal(operator)
                .resourceType(PlatformResourceType.TASK)
                .action(PlatformAction.EDIT)
                .resourceAttributes(Map.of(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, "task:edit"))
                .build());

        assertTrue(allowed.isAllowed());
        assertFalse(denied.isAllowed());
        assertEquals(AuthorizationReasonCode.PERMISSION_DENIED, denied.getReasonCode());
    }

    @Test
    void submitterTaskCreateAuthorizationPreservesProjectEventAndUserScopeChecks() {
        PrincipalContext submitter = PrincipalContext.builder()
                .principalId("crawler-agent")
                .userId("crawler-user")
                .permissions(List.of("task:create"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build();

        Map<String, Object> resourceAttributes = new LinkedHashMap<>();
        resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, "task:create");
        resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_USER_ID, "crawler-user");

        AuthorizationDecision allowed = policy.authorize(AuthorizationRequest.builder()
                .principal(submitter)
                .resourceType(PlatformResourceType.TASK)
                .action(PlatformAction.CREATE)
                .project("crawlerApp")
                .eventCode("crawler.fetch-page")
                .resourceAttributes(resourceAttributes)
                .build());

        AuthorizationDecision deniedProject = policy.authorize(AuthorizationRequest.builder()
                .principal(submitter)
                .resourceType(PlatformResourceType.TASK)
                .action(PlatformAction.CREATE)
                .project("demoApp")
                .eventCode("crawler.fetch-page")
                .resourceAttributes(resourceAttributes)
                .build());

        AuthorizationDecision deniedEvent = policy.authorize(AuthorizationRequest.builder()
                .principal(submitter)
                .resourceType(PlatformResourceType.TASK)
                .action(PlatformAction.CREATE)
                .project("crawlerApp")
                .eventCode("crawler.parse-result")
                .resourceAttributes(resourceAttributes)
                .build());

        Map<String, Object> mismatchedUserAttributes = new LinkedHashMap<>(resourceAttributes);
        mismatchedUserAttributes.put(DefaultAuthorizationPolicy.ATTR_USER_ID, "other-user");
        AuthorizationDecision deniedUser = policy.authorize(AuthorizationRequest.builder()
                .principal(submitter)
                .resourceType(PlatformResourceType.TASK)
                .action(PlatformAction.CREATE)
                .project("crawlerApp")
                .eventCode("crawler.fetch-page")
                .resourceAttributes(mismatchedUserAttributes)
                .build());

        assertTrue(allowed.isAllowed());
        assertFalse(deniedProject.isAllowed());
        assertFalse(deniedEvent.isAllowed());
        assertFalse(deniedUser.isAllowed());
        assertEquals(AuthorizationReasonCode.PROJECT_SCOPE_DENIED, deniedProject.getReasonCode());
        assertEquals(AuthorizationReasonCode.EVENT_SCOPE_DENIED, deniedEvent.getReasonCode());
        assertEquals(AuthorizationReasonCode.USER_SCOPE_DENIED, deniedUser.getReasonCode());
    }

    @Test
    void submitterTaskEditAuthorizationPreservesProjectAndEventScopeChecksWithoutUserScope() {
        PrincipalContext submitter = PrincipalContext.builder()
                .principalId("crawler-agent")
                .userId("crawler-user")
                .permissions(List.of("task:create"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build();

        Map<String, Object> resourceAttributes = Map.of(
                DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, "task:create",
                DefaultAuthorizationPolicy.ATTR_USER_ID, "other-user"
        );

        AuthorizationDecision allowed = policy.authorize(AuthorizationRequest.builder()
                .principal(submitter)
                .resourceType(PlatformResourceType.TASK)
                .action(PlatformAction.EDIT)
                .project("crawlerApp")
                .eventCode("crawler.fetch-page")
                .resourceAttributes(resourceAttributes)
                .build());

        AuthorizationDecision deniedEvent = policy.authorize(AuthorizationRequest.builder()
                .principal(submitter)
                .resourceType(PlatformResourceType.TASK)
                .action(PlatformAction.EDIT)
                .project("crawlerApp")
                .eventCode("crawler.parse-result")
                .resourceAttributes(resourceAttributes)
                .build());

        assertTrue(allowed.isAllowed());
        assertFalse(deniedEvent.isAllowed());
        assertEquals(AuthorizationReasonCode.EVENT_SCOPE_DENIED, deniedEvent.getReasonCode());
    }

    @Test
    void externalWorkerAuthorizationPreservesWorkerBindingAndRegisterScopeChecks() {
        PrincipalContext workerPrincipal = PrincipalContext.builder()
                .principalId("node-worker-1")
                .permissions(List.of("worker:poll"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of("workerId", "node-worker-1"))
                .build();

        Map<String, Object> registerAttributes = new LinkedHashMap<>();
        registerAttributes.put(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, "worker:poll");
        registerAttributes.put(DefaultAuthorizationPolicy.ATTR_EVENT_BINDINGS, List.of(
                WorkerEventBinding.builder()
                        .eventCode("crawler.fetch-page")
                        .projectCodes(List.of("crawlerApp"))
                        .build()
        ));

        AuthorizationDecision allowedRegister = policy.authorize(AuthorizationRequest.builder()
                .principal(workerPrincipal)
                .resourceType(PlatformResourceType.WORKER)
                .action(PlatformAction.REGISTER)
                .workerId("node-worker-1")
                .resourceAttributes(registerAttributes)
                .build());

        AuthorizationDecision deniedWorkerBinding = policy.authorize(AuthorizationRequest.builder()
                .principal(workerPrincipal)
                .resourceType(PlatformResourceType.WORKER)
                .action(PlatformAction.POLL)
                .workerId("other-worker")
                .resourceAttributes(Map.of(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, "worker:poll"))
                .build());

        Map<String, Object> deniedEventAttributes = new LinkedHashMap<>();
        deniedEventAttributes.put(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, "worker:poll");
        deniedEventAttributes.put(DefaultAuthorizationPolicy.ATTR_EVENT_BINDINGS, List.of(
                WorkerEventBinding.builder()
                        .eventCode("mock.reset")
                        .build()
        ));
        AuthorizationDecision deniedEvent = policy.authorize(AuthorizationRequest.builder()
                .principal(workerPrincipal)
                .resourceType(PlatformResourceType.WORKER)
                .action(PlatformAction.REGISTER)
                .workerId("node-worker-1")
                .resourceAttributes(deniedEventAttributes)
                .build());

        assertTrue(allowedRegister.isAllowed());
        assertFalse(deniedWorkerBinding.isAllowed());
        assertFalse(deniedEvent.isAllowed());
        assertEquals(AuthorizationReasonCode.WORKER_BINDING_DENIED, deniedWorkerBinding.getReasonCode());
        assertEquals(AuthorizationReasonCode.EVENT_SCOPE_DENIED, deniedEvent.getReasonCode());
    }
}
