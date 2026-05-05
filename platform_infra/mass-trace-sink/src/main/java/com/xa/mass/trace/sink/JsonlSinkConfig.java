package com.xa.mass.trace.sink;

import java.nio.file.Path;

/**
 * Configuration value object for {@link JsonlExecutionEventSink}.
 *
 * @param directory       directory where JSONL files are written
 * @param rotateSizeBytes rotate the active file when it exceeds this size in bytes (default 64 MB)
 * @param queueCapacity   capacity of the internal async event queue (default 8192)
 */
public record JsonlSinkConfig(
        Path directory,
        long rotateSizeBytes,
        int queueCapacity
) {

    private static final long DEFAULT_ROTATE_SIZE_BYTES = 64L * 1024 * 1024;
    private static final int DEFAULT_QUEUE_CAPACITY = 8192;

    public JsonlSinkConfig {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        if (rotateSizeBytes <= 0) {
            throw new IllegalArgumentException("rotateSizeBytes must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
    }

    /**
     * Returns a config with default rotate size and queue capacity, writing to the given directory.
     */
    public static JsonlSinkConfig defaults(Path directory) {
        return new JsonlSinkConfig(directory, DEFAULT_ROTATE_SIZE_BYTES, DEFAULT_QUEUE_CAPACITY);
    }
}
