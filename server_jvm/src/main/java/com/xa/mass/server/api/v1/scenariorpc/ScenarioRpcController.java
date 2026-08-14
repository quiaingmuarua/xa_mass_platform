package com.xa.mass.server.api.v1.scenariorpc;

import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcCatalogResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcInputUploadResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunRequest;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunResponse;
import com.xa.mass.server.scenariorpc.ScenarioRpcService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@Profile("scenario-workers")
@RequestMapping("/api/v1/scenario-rpc")
public final class ScenarioRpcController {

    private static final MediaType NDJSON = new MediaType(
            "application",
            "x-ndjson"
    );

    private final ScenarioRpcService scenarioRpc;

    public ScenarioRpcController(ScenarioRpcService scenarioRpc) {
        this.scenarioRpc = scenarioRpc;
    }

    @GetMapping("/scenarios")
    public ScenarioRpcCatalogResponse scenarios() {
        return scenarioRpc.scenarios();
    }

    @PostMapping(
            path = "/input-files/{fileName}",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public ScenarioRpcInputUploadResponse upload(
            @PathVariable @NotBlank String fileName,
            @RequestBody byte[] content
    ) {
        return scenarioRpc.upload(fileName, content);
    }

    @PostMapping("/runs")
    public ScenarioRpcRunResponse run(
            @RequestBody ScenarioRpcRunRequest request
    ) {
        return scenarioRpc.run(request);
    }

    @GetMapping("/output-files/{fileName}")
    public ResponseEntity<byte[]> download(
            @PathVariable @NotBlank String fileName
    ) {
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName)
                                .build()
                                .toString()
                )
                .body(scenarioRpc.download(fileName));
    }
}
