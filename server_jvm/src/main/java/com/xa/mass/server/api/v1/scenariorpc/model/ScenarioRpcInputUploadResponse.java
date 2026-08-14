package com.xa.mass.server.api.v1.scenariorpc.model;

public record ScenarioRpcInputUploadResponse(
        String fileName,
        long byteCount,
        int lineCount
) {
}
