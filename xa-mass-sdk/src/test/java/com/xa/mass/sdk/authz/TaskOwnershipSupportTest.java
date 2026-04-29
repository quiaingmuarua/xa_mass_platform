package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.model.JsonInput;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskOwnershipSupportTest {

    @Test
    void stampMassTaskCreateRequestWritesReservedSecurityMetadata() {
        MassTaskCreateRequest request = MassTaskCreateRequest.builder()
                .userId("sdk-user")
                .project("demoApp")
                .taskName("task-a")
                .sharedConfig(Map.of("source", "sdk"))
                .inputs(List.of(Map.of("target", "alpha")))
                .build();

        PrincipalContext principal = PrincipalContext.builder()
                .principalId("sdk-internal")
                .principalType(PrincipalType.SERVICE)
                .userId("sdk-user")
                .build();

        MassTaskCreateRequest stamped = TaskOwnershipSupport.stamp(request, principal);
        TaskOwnershipStamp ownershipStamp = TaskOwnershipStamp.fromSharedConfig(stamped.getSharedConfig());

        assertNotNull(ownershipStamp);
        assertEquals("sdk-internal", ownershipStamp.getCreatedByPrincipalId());
        assertEquals(PrincipalType.SERVICE, ownershipStamp.getCreatedByPrincipalType());
        assertEquals("sdk", stamped.getSharedConfig().get("source"));
    }

    @Test
    void stampMassTaskRequestWritesReservedSecurityMetadata() {
        MassTaskRequest request = MassTaskRequest.builder()
                .userId("crawler-user")
                .project("crawlerApp")
                .taskName("crawler-task")
                .eventCode("crawler.fetch-page")
                .mode(TaskMode.STREAMING)
                .payloadType(PayloadType.JSON)
                .sharedConfig(Map.of("source", "submitter"))
                .inputs(List.of(new JsonInput(Map.of("url", "https://example.test"))))
                .build();

        PrincipalContext principal = PrincipalContext.builder()
                .principalId("crawler-agent")
                .principalType(PrincipalType.SERVICE)
                .userId("crawler-user")
                .build();

        MassTaskRequest stamped = TaskOwnershipSupport.stamp(request, principal);
        TaskOwnershipStamp ownershipStamp = TaskOwnershipStamp.fromSharedConfig(stamped.getSharedConfig());

        assertNotNull(ownershipStamp);
        assertEquals("crawler-agent", ownershipStamp.getCreatedByPrincipalId());
        assertEquals(PrincipalType.SERVICE, ownershipStamp.getCreatedByPrincipalType());
        assertEquals("submitter", stamped.getSharedConfig().get("source"));
    }

    @Test
    void stampPreservesExistingReservedSecurityMetadata() {
        MassTaskRequest request = MassTaskRequest.builder()
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
                .inputs(List.of(new JsonInput(Map.of("url", "https://example.test"))))
                .build();

        PrincipalContext principal = PrincipalContext.builder()
                .principalId("sdk-internal")
                .principalType(PrincipalType.SERVICE)
                .userId("crawler-user")
                .build();

        MassTaskRequest stamped = TaskOwnershipSupport.stamp(request, principal);
        TaskOwnershipStamp ownershipStamp = TaskOwnershipStamp.fromSharedConfig(stamped.getSharedConfig());

        assertNotNull(ownershipStamp);
        assertEquals("crawler-agent", ownershipStamp.getCreatedByPrincipalId());
        assertEquals(PrincipalType.SERVICE, ownershipStamp.getCreatedByPrincipalType());
        assertEquals("submitter", stamped.getSharedConfig().get("source"));
    }
}
