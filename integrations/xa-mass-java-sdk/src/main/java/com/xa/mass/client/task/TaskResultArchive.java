package com.xa.mass.client.task;

public record TaskResultArchive(
        boolean ready,
        String taskId,
        String format,
        String contentType,
        String contentEncoding,
        long itemCount,
        Long byteSize,
        String checksum,
        String downloadUrl
) {
}
