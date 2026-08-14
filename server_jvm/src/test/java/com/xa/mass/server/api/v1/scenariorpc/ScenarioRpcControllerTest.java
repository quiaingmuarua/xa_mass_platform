package com.xa.mass.server.api.v1.scenariorpc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcCreateResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcInputUploadResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcInstanceResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcTypeCatalogResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcTypeView;
import com.xa.mass.server.scenariorpc.ScenarioRpcService;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ScenarioRpcControllerTest {

    private static final Instant CREATED_AT = Instant.parse(
            "2026-08-14T12:00:00Z"
    );

    private ScenarioRpcService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ScenarioRpcService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new ScenarioRpcController(service)
        ).build();
    }

    @Test
    void remainsProxyableForMethodValidation() {
        org.assertj.core.api.Assertions.assertThat(Modifier.isFinal(
                ScenarioRpcController.class.getModifiers()
        )).isFalse();
    }

    @Test
    void exposesCreateRunQueryAndDownloadContracts() throws Exception {
        when(service.scenarioTypes()).thenReturn(
                new ScenarioRpcTypeCatalogResponse(List.of(
                        new ScenarioRpcTypeView(
                                "string.md5",
                                "scenario-string-utils-workers",
                                "string.md5"
                        )
                ))
        );
        when(service.create(any())).thenReturn(new ScenarioRpcCreateResponse(
                "scenario-1786680000123",
                "string.md5",
                "created"
        ));
        when(service.upload(any(), any())).thenReturn(
                new ScenarioRpcInputUploadResponse("strings.txt", 11, 2)
        );
        when(service.run(eq("scenario-1786680000123"), any())).thenReturn(
                new ScenarioRpcRunResponse(
                        "scenario-1786680000123",
                        "succeeded",
                        "strings.txt",
                        2,
                        2,
                        0,
                        1,
                        25,
                        "scenario-1786680000123.jsonl"
                )
        );
        when(service.get("scenario-1786680000123")).thenReturn(
                new ScenarioRpcInstanceResponse(
                        "scenario-1786680000123",
                        "string.md5",
                        "succeeded",
                        CREATED_AT,
                        "strings.txt",
                        2,
                        2,
                        0,
                        1,
                        25,
                        "scenario-1786680000123.jsonl"
                )
        );
        when(service.download("scenario-1786680000123.jsonl")).thenReturn(
                "{}\n".getBytes(StandardCharsets.UTF_8)
        );

        mvc.perform(get("/api/v1/scenario-rpc/scenario-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioTypes[0].scenarioType")
                        .value("string.md5"));
        mvc.perform(post("/api/v1/scenario-rpc/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioType":"string.md5"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/scenario-rpc/scenarios/"
                                + "scenario-1786680000123"
                ))
                .andExpect(jsonPath("$.status").value("created"));
        mvc.perform(post("/api/v1/scenario-rpc/input-files/strings.txt")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello\nworld"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(2));
        mvc.perform(post(
                        "/api/v1/scenario-rpc/scenarios/"
                                + "scenario-1786680000123:run"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputFile":"strings.txt",
                                  "loadIntervalMillis":100,
                                  "maximumLoadRounds":300
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.remainingCount").value(0))
                .andExpect(jsonPath("$.outputFile").value(
                        "scenario-1786680000123.jsonl"
                ))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("taskId")
                )));
        mvc.perform(get(
                        "/api/v1/scenario-rpc/scenarios/"
                                + "scenario-1786680000123"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioType")
                        .value("string.md5"));
        mvc.perform(get(
                        "/api/v1/scenario-rpc/output-files/"
                                + "scenario-1786680000123.jsonl"
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "scenario-1786680000123.jsonl"
                        )
                ))
                .andExpect(content().contentType("application/x-ndjson"))
                .andExpect(content().bytes("{}\n".getBytes(
                        StandardCharsets.UTF_8
                )));
    }

    @Test
    void doesNotExposeRemovedCatalogOrRunsRoutes() throws Exception {
        mvc.perform(get("/api/v1/scenario-rpc/scenarios"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/scenario-rpc/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
