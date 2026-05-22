package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.base.event.PriorityClass;
import com.xa.mass.base.event.ResponseMode;
import com.xa.mass.base.event.TargetScope;
import com.xa.mass.sdk.SubmitterOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterProfile;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectApiControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProjectEventCatalogRegistry catalog = DefaultProjectEventCatalogFactory.createDefaultProjectRegistry();
        catalog.registerEventDefinition(EventDefinition.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Example crawler fetch task event.")
                .payloadTypes(java.util.List.of(PayloadType.JSON))
                .taskModes(java.util.List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .projectCodes(java.util.List.of("crawlerApp", "demoApp", "demoApp"))
                .priorityClass(PriorityClass.BULK)
                .responseMode(ResponseMode.FINAL_RESULT)
                .targetScope(TargetScope.WORKER)
                .build());
        catalog.registerEventDefinition(EventDefinition.builder()
                .code("chatbot.reply")
                .name("Chatbot Reply")
                .description("Example chatbot reply task event.")
                .payloadTypes(java.util.List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(java.util.List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .priorityClass(PriorityClass.INTERACTIVE)
                .build());
        catalog.registerProject(ProjectDefinition.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Test demo app")
                .eventCodes(java.util.List.of("crawler.fetch-page", "chatbot.reply"))
                .build());
        catalog.registerProject(ProjectDefinition.builder()
                .code("crawlerApp")
                .name("Crawler App")
                .description("Test crawler app")
                .eventCodes(java.util.List.of("crawler.fetch-page"))
                .build());
        SubmitterOperations submitterOperations = mock(SubmitterOperations.class);
        when(submitterOperations.listSubmitters()).thenReturn(List.of(
                SubmitterProfile.builder()
                        .principalId("crawler-submitter")
                        .projectScope("crawlerApp")
                        .projectScopes(List.of("crawlerApp"))
                        .enabled(true)
                        .build(),
                SubmitterProfile.builder()
                        .principalId("demo-admin")
                        .projectScopes(List.of("*"))
                        .enabled(true)
                        .build(),
                SubmitterProfile.builder()
                        .principalId("test-only")
                        .projectScopes(List.of("testApp"))
                        .enabled(true)
                        .build()
        ));
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ProjectApiController(catalog, submitterOperations)
        ).build();
    }

    @Test
    void listProjectsReturnsProjectDefinitions() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.code=='demoApp')]").exists())
                .andExpect(jsonPath("$.data[0].eventCodes").isArray());
    }

    @Test
    void projectEventsReturnResolvedEventDefinitions() throws Exception {
        mockMvc.perform(get("/api/v1/projects/demoApp/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='chatbot.reply')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page' && @.priorityClass=='BULK')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page' && @.responseMode=='FINAL_RESULT')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page' && @.deliveryAcknowledgementMode=='NONE')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page' && @.convergenceMode=='FINAL_RESULT')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page' && @.targetScope=='WORKER')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='chatbot.reply' && @.priorityClass=='INTERACTIVE')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='chatbot.reply' && @.responseMode=='FINAL_RESULT')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='chatbot.reply' && @.deliveryAcknowledgementMode=='NONE')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='chatbot.reply' && @.convergenceMode=='FINAL_RESULT')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='chatbot.reply' && @.targetScope=='WORKER')]").exists());
    }

    @Test
    void projectSubmittersReturnScopedAndWildcardPrincipals() throws Exception {
        mockMvc.perform(get("/api/v1/projects/crawlerApp/submitters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.principalId=='crawler-submitter')]").exists())
                .andExpect(jsonPath("$.data[?(@.principalId=='demo-admin')]").exists())
                .andExpect(jsonPath("$.data[?(@.principalId=='test-only')]").doesNotExist());
    }

    @Test
    void scopedSubmitterOnlySeesAuthorizedProjectsAndEvents() throws Exception {
        PrincipalContext principal = PrincipalContext.builder()
                .principalId("crawler-reader")
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build();

        mockMvc.perform(get("/api/v1/projects")
                        .requestAttr(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR, principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code=='crawlerApp')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='demoApp')]").doesNotExist());

        mockMvc.perform(get("/api/v1/projects/crawlerApp/events")
                        .requestAttr(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR, principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='chatbot.reply')]").doesNotExist());
    }

    @Test
    void scopedSubmitterCannotEnumerateOtherProjectResources() throws Exception {
        PrincipalContext principal = PrincipalContext.builder()
                .principalId("crawler-reader")
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build();

        mockMvc.perform(get("/api/v1/projects/demoApp")
                        .requestAttr(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR, principal))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/projects/demoApp/events")
                        .requestAttr(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR, principal))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/projects/demoApp/submitters")
                        .requestAttr(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR, principal))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void legacyMetaProjectRoutesAreNotMapped() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/projects"))
                .andExpect(status().isNotFound());
    }
}
