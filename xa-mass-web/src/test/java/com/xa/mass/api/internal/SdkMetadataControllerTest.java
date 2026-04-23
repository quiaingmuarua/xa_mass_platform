package com.xa.mass.api.internal;

import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.EventMetadata;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.TaskMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SdkMetadataControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProjectEventCatalogRegistry catalog = DefaultProjectEventCatalogFactory.createDefaultRegistry();
        catalog.registerEvent(EventMetadata.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Example crawler fetch task event.")
                .payloadTypes(java.util.List.of(PayloadType.JSON))
                .taskModes(java.util.List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        catalog.registerEvent(EventMetadata.builder()
                .code("chatbot.reply")
                .name("Chatbot Reply")
                .description("Example chatbot reply task event.")
                .payloadTypes(java.util.List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(java.util.List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        catalog.registerEvent(EventMetadata.builder()
                .code("sms.wait-code")
                .name("SMS Wait Code")
                .description("Example sms wait-code task event.")
                .payloadTypes(java.util.List.of(PayloadType.JSON))
                .taskModes(java.util.List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        catalog.registerProject(ProjectMetadata.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Test demo app")
                .eventCodes(java.util.List.of("crawler.fetch-page", "chatbot.reply"))
                .build());
        mockMvc = MockMvcBuilders.standaloneSetup(new SdkMetadataController(catalog)).build();
    }

    @Test
    void listProjectsReturnsProjectMetadata() throws Exception {
        mockMvc.perform(get("/sdk/meta/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.code=='demoApp')]").exists())
                .andExpect(jsonPath("$.data[0].eventCodes").isArray());
    }

    @Test
    void projectEventsReturnResolvedEventMetadata() throws Exception {
        mockMvc.perform(get("/sdk/meta/projects/demoApp/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='chatbot.reply')]").exists());
    }

    @Test
    void listEventsReturnsEventMetadata() throws Exception {
        mockMvc.perform(get("/sdk/meta/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.code=='sms.wait-code')]").exists());
    }

    @Test
    void missingProjectOrEventReturnsNotFound() throws Exception {
        mockMvc.perform(get("/sdk/meta/projects/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(get("/sdk/meta/events/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
