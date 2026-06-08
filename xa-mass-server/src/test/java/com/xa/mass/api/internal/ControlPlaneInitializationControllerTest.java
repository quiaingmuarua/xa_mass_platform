package com.xa.mass.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.aop.GlobalExceptionHandler;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.storage.memory.InMemoryCatalogMetadataStore;
import com.xa.mass.storage.memory.InMemoryRuleStorage;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControlPlaneInitializationControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void catalogSyncWritesStoreAndLiveApplicationCatalog() throws Exception {
        MassSdkApplication app = mock(MassSdkApplication.class);
        InMemoryCatalogMetadataStore catalogStore = new InMemoryCatalogMetadataStore();
        InMemoryRuleStorage ruleStorage = new InMemoryRuleStorage();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new ControlPlaneInitializationController(app, catalogStore, ruleStorage)
        ).setControllerAdvice(new GlobalExceptionHandler()).build();

        mockMvc.perform(post("/api/v1/control-plane/catalog:sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "events", List.of(Map.of(
                                        "code", "crawler.fetch-page",
                                        "name", "Crawler Fetch Page",
                                        "payloadTypes", List.of("JSON"),
                                        "taskModes", List.of("SINGLE_RUN"),
                                        "projectCodes", List.of("crawlerApp")
                                )),
                                "projects", List.of(Map.of(
                                        "code", "crawlerApp",
                                        "name", "Crawler",
                                        "eventCodes", List.of("crawler.fetch-page")
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events").value(1))
                .andExpect(jsonPath("$.data.projects").value(1));

        assertThat(catalogStore.getEvent("crawler.fetch-page")).isPresent();
        assertThat(catalogStore.getProject("crawlerApp")).isPresent();
        verify(app, times(1)).registerProject(any());
        verify(app, times(1)).registerEventDefinition(any());
    }

    @Test
    void catalogSyncRejectsMissingProjectEventReference() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new ControlPlaneInitializationController(
                        mock(MassSdkApplication.class),
                        new InMemoryCatalogMetadataStore(),
                        new InMemoryRuleStorage())
        ).setControllerAdvice(new GlobalExceptionHandler()).build();

        mockMvc.perform(post("/api/v1/control-plane/catalog:sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "events", List.of(),
                                "projects", List.of(Map.of(
                                        "code", "crawlerApp",
                                        "name", "Crawler",
                                        "eventCodes", List.of("missing.event")
                                ))
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ruleSyncUpsertsWithoutDeletingExistingRules() throws Exception {
        InMemoryRuleStorage ruleStorage = new InMemoryRuleStorage();
        RuleDefinition baseline = rule("baseline", "hasWorkerSchedulingResource == true");
        ruleStorage.addRule(baseline);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new ControlPlaneInitializationController(
                        mock(MassSdkApplication.class),
                        new InMemoryCatalogMetadataStore(),
                        ruleStorage)
        ).setControllerAdvice(new GlobalExceptionHandler()).build();

        mockMvc.perform(post("/api/v1/control-plane/rules:sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "rules", List.of(rule("scenario-rule", "matchesTargetWorkerAttributes == true"))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rules").value(1));

        assertThat(ruleStorage.getRule("baseline")).isPresent();
        assertThat(ruleStorage.getRule("scenario-rule")).isPresent();
    }

    private static RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setName(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        rule.setEnabled(true);
        return rule;
    }
}
