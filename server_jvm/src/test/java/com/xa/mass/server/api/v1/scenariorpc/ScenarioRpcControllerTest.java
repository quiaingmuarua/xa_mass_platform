package com.xa.mass.server.api.v1.scenariorpc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcCatalogResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcDescriptorView;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcInputUploadResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunResponse;
import com.xa.mass.server.scenariorpc.ScenarioRpcService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ScenarioRpcControllerTest {

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
    void exposesCatalogUploadRunAndDownloadContracts()
            throws Exception {
        when(service.scenarios()).thenReturn(new ScenarioRpcCatalogResponse(
                List.of(new ScenarioRpcDescriptorView(
                        "string.md5",
                        "scenario-string-utils-workers",
                        "string.md5"
                ))
        ));
        when(service.upload(any(), any())).thenReturn(
                new ScenarioRpcInputUploadResponse(
                        "strings.txt",
                        11,
                        2
                )
        );
        when(service.run(any())).thenReturn(new ScenarioRpcRunResponse(
                "string.md5",
                "scenario-string-utils-workers",
                "string.md5",
                "strings.txt",
                "string.md5-1.jsonl",
                2,
                2,
                10,
                Instant.parse("2026-08-14T12:00:00Z")
        ));
        when(service.download("string.md5-1.jsonl")).thenReturn(
                "{}\n".getBytes(StandardCharsets.UTF_8)
        );

        mvc.perform(get("/api/v1/scenario-rpc/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarios[0].scenarioId")
                        .value("string.md5"));
        mvc.perform(post(
                        "/api/v1/scenario-rpc/input-files/strings.txt"
                )
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello\nworld"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(2));
        mvc.perform(post("/api/v1/scenario-rpc/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scenarioId":"string.md5",
                                  "inputFile":"strings.txt",
                                  "concurrency":2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputFile")
                        .value("string.md5-1.jsonl"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("taskId")
                )));
        mvc.perform(get(
                        "/api/v1/scenario-rpc/output-files/"
                                + "string.md5-1.jsonl"
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "string.md5-1.jsonl"
                        )
                ))
                .andExpect(content().contentType("application/x-ndjson"))
                .andExpect(content().bytes("{}\n".getBytes(
                        StandardCharsets.UTF_8
                )));
    }
}
