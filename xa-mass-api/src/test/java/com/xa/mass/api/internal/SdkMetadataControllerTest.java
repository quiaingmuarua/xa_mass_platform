package com.xa.mass.api.internal;

import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
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
        ProjectEventCatalog catalog = DefaultProjectEventCatalogFactory.createDefaultRegistry();
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
