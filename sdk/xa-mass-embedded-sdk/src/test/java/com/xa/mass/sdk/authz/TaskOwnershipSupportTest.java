package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskOwnershipSupportTest {

    @Test
    void stampMassTaskShellCreateRequestWritesReservedSecurityMetadata() {
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("sdk-user")
                .project("demoApp")
                .sharedConfig(Map.of("source", "sdk"))
                .build();

        PrincipalContext principal = PrincipalContext.builder()
                .principalId("sdk-internal")
                .principalType(PrincipalType.SERVICE)
                .userId("sdk-user")
                .build();

        MassTaskShellCreateRequest stamped = TaskOwnershipSupport.stamp(request, principal);
        TaskOwnershipStamp ownershipStamp = TaskOwnershipStamp.fromSharedConfig(stamped.getSharedConfig());

        assertNotNull(ownershipStamp);
        assertEquals("sdk-internal", ownershipStamp.getCreatedByPrincipalId());
        assertEquals(PrincipalType.SERVICE, ownershipStamp.getCreatedByPrincipalType());
        assertEquals("sdk", stamped.getSharedConfig().get("source"));
    }

    @Test
    void stampMassTaskShellCreateRequestPreservesSharedConfig() {
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("crawler-user")
                .project("crawlerApp")
                .sharedConfig(Map.of("source", "task-api-key"))
                .build();

        PrincipalContext principal = PrincipalContext.builder()
                .principalId("crawler-agent")
                .principalType(PrincipalType.SERVICE)
                .userId("crawler-user")
                .build();

        MassTaskShellCreateRequest stamped = TaskOwnershipSupport.stamp(request, principal);
        TaskOwnershipStamp ownershipStamp = TaskOwnershipStamp.fromSharedConfig(stamped.getSharedConfig());

        assertNotNull(ownershipStamp);
        assertEquals("crawler-agent", ownershipStamp.getCreatedByPrincipalId());
        assertEquals(PrincipalType.SERVICE, ownershipStamp.getCreatedByPrincipalType());
        assertEquals("task-api-key", stamped.getSharedConfig().get("source"));
    }

    @Test
    void stampPreservesExistingReservedSecurityMetadata() {
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("crawler-user")
                .project("crawlerApp")
                .sharedConfig(TaskOwnershipStamp.applyToSharedConfig(
                        Map.of("source", "task-api-key"),
                        new TaskOwnershipStamp("crawler-agent", PrincipalType.SERVICE)
                ))
                .build();

        PrincipalContext principal = PrincipalContext.builder()
                .principalId("sdk-internal")
                .principalType(PrincipalType.SERVICE)
                .userId("crawler-user")
                .build();

        MassTaskShellCreateRequest stamped = TaskOwnershipSupport.stamp(request, principal);
        TaskOwnershipStamp ownershipStamp = TaskOwnershipStamp.fromSharedConfig(stamped.getSharedConfig());

        assertNotNull(ownershipStamp);
        assertEquals("crawler-agent", ownershipStamp.getCreatedByPrincipalId());
        assertEquals(PrincipalType.SERVICE, ownershipStamp.getCreatedByPrincipalType());
        assertEquals("task-api-key", stamped.getSharedConfig().get("source"));
    }

    @Test
    void authorizeOwnershipAllowsMatchingPrincipal() {
        PrincipalContext principal = PrincipalContext.builder()
                .principalId("crawler-agent")
                .principalType(PrincipalType.SERVICE)
                .build();

        AuthorizationDecision decision = TaskOwnershipSupport.authorizeOwnership(
                principal,
                TaskOwnershipStamp.applyToSharedConfig(
                        Map.of("source", "task-api-key"),
                        new TaskOwnershipStamp("crawler-agent", PrincipalType.SERVICE)
                )
        );

        assertTrue(decision.isAllowed());
        assertEquals(AuthorizationReasonCode.ALLOWED, decision.getReasonCode());
    }

    @Test
    void authorizeOwnershipRejectsMissingStamp() {
        PrincipalContext principal = PrincipalContext.builder()
                .principalId("crawler-agent")
                .principalType(PrincipalType.SERVICE)
                .build();

        AuthorizationDecision decision = TaskOwnershipSupport.authorizeOwnership(principal, Map.of("source", "task-api-key"));

        assertFalse(decision.isAllowed());
        assertEquals(AuthorizationReasonCode.OWNERSHIP_STAMP_MISSING, decision.getReasonCode());
    }

    @Test
    void authorizeOwnershipRejectsMismatchedPrincipal() {
        PrincipalContext principal = PrincipalContext.builder()
                .principalId("other-agent")
                .principalType(PrincipalType.SERVICE)
                .build();

        AuthorizationDecision decision = TaskOwnershipSupport.authorizeOwnership(
                principal,
                TaskOwnershipStamp.applyToSharedConfig(
                        Map.of("source", "task-api-key"),
                        new TaskOwnershipStamp("crawler-agent", PrincipalType.SERVICE)
                )
        );

        assertFalse(decision.isAllowed());
        assertEquals(AuthorizationReasonCode.OWNER_MISMATCH, decision.getReasonCode());
    }
}
