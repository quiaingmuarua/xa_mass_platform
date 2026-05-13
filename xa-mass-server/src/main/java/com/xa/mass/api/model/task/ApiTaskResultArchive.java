package com.xa.mass.api.model.task;

public record ApiTaskResultArchive(
        boolean ready,
        String taskId,
        String format,
        String contentType,
        String contentEncoding,
        long itemCount,
        long byteSize,
        String checksum,
        String downloadUrl
) {
}
