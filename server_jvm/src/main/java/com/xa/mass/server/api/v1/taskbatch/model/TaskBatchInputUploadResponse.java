package com.xa.mass.server.api.v1.taskbatch.model;

public record TaskBatchInputUploadResponse(
        String fileName,
        long byteCount,
        int lineCount
) {
}
