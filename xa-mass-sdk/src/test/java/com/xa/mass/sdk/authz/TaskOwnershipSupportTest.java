package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;
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
                .taskName("task-a")
                .eventCode("demo.task")
                .mode(TaskMode.SINGLE_RUN)
                .payloadType(PayloadType.JSON)
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
    void stampMassTaskShellCreateRequestPreservesModeAndPayloadType() {
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("crawler-user")
                .project("crawlerApp")
                .taskName("crawler-task")
                .eventCode("crawler.fetch-page")
                .mode(TaskMode.STREAMING)
                .payloadType(PayloadType.JSON)
                .sharedConfig(Map.of("source", "submitter"))
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
        assertEquals("submitter", stamped.getSharedConfig().get("source"));
        assertEquals(TaskMode.STREAMING, stamped.getMode());
        assertEquals(PayloadType.JSON, stamped.getPayloadType());
    }

    @Test
    void stampPreservesExistingReservedSecurityMetadata() {
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("crawler-user")
                .project("crawlerApp")
                .taskName("crawler-task")
                .eventCode("crawler.fetch-page")
                .mode(TaskMode.SINGLE_RUN)
                .payloadType(PayloadType.JSON)
                .sharedConfig(TaskOwnershipStamp.applyToSharedConfig(
                        Map.of("source", "submitter"),
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
        assertEquals("submitter", stamped.getSharedConfig().get("source"));
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
                        Map.of("source", "submitter"),
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

        AuthorizationDecision decision = TaskOwnershipSupport.authorizeOwnership(principal, Map.of("source", "submitter"));

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
                        Map.of("source", "submitter"),
                        new TaskOwnershipStamp("crawler-agent", PrincipalType.SERVICE)
                )
        );

        assertFalse(decision.isAllowed());
        assertEquals(AuthorizationReasonCode.OWNER_MISMATCH, decision.getReasonCode());
    }
}
