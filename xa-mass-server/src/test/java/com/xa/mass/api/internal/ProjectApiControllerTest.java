package com.xa.mass.api.internal;

import com.xa.mass.sdk.SubmitterOperations;
import com.xa.mass.sdk.auth.SubmitterMetadata;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectMetadata;
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
                .build());
        catalog.registerEventDefinition(EventDefinition.builder()
                .code("chatbot.reply")
                .name("Chatbot Reply")
                .description("Example chatbot reply task event.")
                .payloadTypes(java.util.List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(java.util.List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        catalog.registerProject(ProjectMetadata.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Test demo app")
                .eventCodes(java.util.List.of("crawler.fetch-page", "chatbot.reply"))
                .build());
        catalog.registerProject(ProjectMetadata.builder()
                .code("crawlerApp")
                .name("Crawler App")
                .description("Test crawler app")
                .eventCodes(java.util.List.of("crawler.fetch-page"))
                .build());
        SubmitterOperations submitterOperations = mock(SubmitterOperations.class);
        when(submitterOperations.listSubmitters()).thenReturn(List.of(
                SubmitterMetadata.builder()
                        .principalId("crawler-submitter")
                        .projectScope("crawlerApp")
                        .projectScopes(List.of("crawlerApp"))
                        .enabled(true)
                        .build(),
                SubmitterMetadata.builder()
                        .principalId("demo-admin")
                        .projectScopes(List.of("*"))
                        .enabled(true)
                        .build(),
                SubmitterMetadata.builder()
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
    void listProjectsReturnsProjectMetadata() throws Exception {
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
                .andExpect(jsonPath("$.data[?(@.code=='chatbot.reply')]").exists());
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
    void missingProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void legacyMetaProjectRoutesAreNotMapped() throws Exception {
        mockMvc.perform(get("/api/v1/meta/projects"))
                .andExpect(status().isNotFound());
    }
}
