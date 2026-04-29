package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultEventPermissionServiceTest {

    @Test
    void authorizeUsesGlobalEventIdentityAndProjectAsScopeOnly() {
        ProjectEventCatalogRegistry catalog = new ProjectEventCatalogRegistry();
        catalog.registerProject(ProjectMetadata.builder()
                .code("demoApp")
                .name("Demo App")
                .eventCodes(List.of("crawler.fetch-page"))
                .build());
        catalog.registerProject(ProjectMetadata.builder()
                .code("crawlerApp")
                .name("Crawler App")
                .eventCodes(List.of("crawler.fetch-page"))
                .build());

        catalog.registerEventDefinition(EventDefinition.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("demoApp", "crawlerApp"))
                .build());

        DefaultEventPermissionService service = new DefaultEventPermissionService(catalog);

        PrincipalContext principal = PrincipalContext.builder()
                .principalId("client-a")
                .userId("user-a")
                .projectScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .eventScopes(List.of("crawler.fetch-page"))
                .build();
        assertTrue(service.authorize(principal, EventRequest.builder()
                .event("crawler.fetch-page")
                .project("demoApp")
                .requestId("req-demo")
                .build()).isAllowed());
        assertTrue(service.authorize(principal, EventRequest.builder()
                .event("crawler.fetch-page")
                .project("crawlerApp")
                .requestId("req-crawler")
                .build()).isAllowed());

        AuthorizationDecision denied = service.authorize(principal, EventRequest.builder()
                .event("crawler.fetch-page")
                .project("otherApp")
                .requestId("req-other")
                .build());
        assertFalse(denied.isAllowed());
        assertEquals(AuthorizationReasonCode.PROJECT_EVENT_UNSUPPORTED, denied.getReasonCode());
        assertTrue(denied.getReason().contains("project does not support event"));
    }
}
